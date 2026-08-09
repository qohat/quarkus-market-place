package com.marketplace.catalog.infrastructure.messaging;

import com.marketplace.catalog.application.ListingCatalog;
import com.marketplace.catalog.domain.ProductListing;
import com.marketplace.inventory.application.Inventory;
import com.marketplace.inventory.domain.BuyerId;
import com.marketplace.shared.domain.Money;
import com.marketplace.shared.domain.SellerId;
import com.marketplace.shared.outbox.OutboxRelay;
import com.marketplace.support.DatabaseCleaner;
import com.marketplace.support.TransactionalRunner;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kafka.InjectKafkaCompanion;
import io.quarkus.test.kafka.KafkaCompanionResource;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import jakarta.inject.Inject;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El mensaje envenenado: el modo de fallo que no existe en HTTP.
 *
 * <p>Si un mensaje no se puede procesar <em>nunca</em> —un payload corrupto, un identificador
 * imposible— y la estrategia es reintentar, ese mensaje <strong>bloquea su partición para
 * siempre</strong> y nada de lo que venga detrás se procesa jamás. El sistema no da errores
 * llamativos: simplemente deja de avanzar para una parte del tráfico.
 *
 * <p>Con {@code failure-strategy=dead-letter-queue}, el mensaje se aparta a otro tema con la causa
 * en sus cabeceras y el consumidor sigue. Es la única estrategia que no obliga a elegir entre
 * pararlo todo ({@code fail}) y perder datos en silencio ({@code ignore}).
 *
 * <p>Estos tests demuestran las dos mitades: que lo roto se aparta, y —lo que de verdad importa—
 * <strong>que lo siguiente se sigue procesando</strong>.
 */
@QuarkusTest
@QuarkusTestResource(KafkaCompanionResource.class)
@DisplayName("Cola de mensajes muertos")
class DeadLetterQueueTest {

    private static final String TEMA = "marketplace-events";
    private static final String TEMA_DLQ = "marketplace-events-dlq";
    private static final BuyerId COMPRADOR = new BuyerId(UUID.randomUUID());

    @InjectKafkaCompanion
    KafkaCompanion kafka;

    @Inject
    ListingCatalog catalog;

    @Inject
    Inventory inventory;

    @Inject
    OutboxRelay relay;

    @Inject
    DatabaseCleaner database;

    @Inject
    TransactionalRunner tx;

    @BeforeEach
    void setUp() {
        database.clear();
    }

    @AfterEach
    void tearDown() {
        database.clear();
    }

    @Test
    @DisplayName("un mensaje NUESTRO pero corrupto acaba en la cola de muertos")
    void aPoisonMessageEndsUpInTheDeadLetterQueue() {
        // Tiene la forma de un StockChanged —deserializa sin problema— pero el listingId no es
        // un UUID. Es el caso peligroso: no se puede descartar como «no es para mí», y ningún
        // reintento lo va a arreglar.
        // Marcador único: la cola de muertos es un tema compartido y persiste entre tests, así
        // que buscar «no-soy-un-uuid» a secas encontraba el veneno de OTRO test. Un test que
        // pasa por el mensaje equivocado no prueba nada.
        var marcador = "roto-" + UUID.randomUUID();
        var envenenado = """
                {"listingId":"%s","onHand":10,"reserved":0,"available":10}
                """.formatted(marcador);

        kafka.produce(String.class, String.class)
                .fromRecords(new org.apache.kafka.clients.producer.ProducerRecord<>(
                        TEMA, UUID.randomUUID().toString(), envenenado));

        var enviados = kafka.consume(String.class, String.class)
                .withGroupId("dlq-observer-" + UUID.randomUUID())
                .withAutoCommit()
                .fromTopics(TEMA_DLQ)
                .awaitRecords(1, Duration.ofSeconds(30));

        // Se busca el mensaje ENTRE los recibidos en vez de asumir que es el primero: la DLQ es
        // un tema compartido y puede traer restos de otras ejecuciones.
        assertTrue(enviados.getRecords().stream().anyMatch(r -> r.value().contains(marcador)),
                "el mensaje corrupto debería estar en la DLQ");
    }

    @Test
    @DisplayName("LO IMPORTANTE: tras el envenenado, los mensajes siguientes se siguen procesando")
    void theConsumerKeepsGoingAfterAPoisonMessage() {
        var seller = SellerId.newId();
        var listing = tx.call(() ->
                catalog.createProduct(seller, "Teclado", Money.of("25.00", "EUR"), 10));

        // Primero, veneno.
        kafka.produce(String.class, String.class)
                .fromRecords(new org.apache.kafka.clients.producer.ProducerRecord<>(
                        TEMA, listing.id().toString(),
                        "{\"listingId\":\"roto\",\"onHand\":1,\"reserved\":0,\"available\":1}"));

        // Y detrás, un evento perfectamente válido con la MISMA clave, así que va a la misma
        // partición: si el envenenado la hubiera bloqueado, este no llegaría nunca.
        tx.run(() -> inventory.track(listing.id(), 10));
        tx.run(() -> inventory.reserve(listing.id(), COMPRADOR, 3));
        tx.run(() -> relay.publishPending());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertEquals(7, tx.call(() ->
                        ((ProductListing) catalog.byId(listing.id())).availableStock())));
    }

    @Test
    @DisplayName("un evento de OTRO tipo se ignora sin acabar en la cola de muertos")
    void anUnrelatedEventIsIgnoredNotDeadLettered() {
        // El tema es compartido, así que aquí llega de todo. Mandar estos mensajes a la DLQ la
        // llenaría de eventos perfectamente sanos de otros consumidores, y acabaría siendo
        // ruido que nadie revisa — que es como mueren las colas de muertos en la práctica.
        kafka.produce(String.class, String.class)
                .fromRecords(new org.apache.kafka.clients.producer.ProducerRecord<>(
                        TEMA, UUID.randomUUID().toString(),
                        "{\"tipo\":\"OtraCosaCompletamenteDistinta\",\"dato\":42}"));

        var enviados = kafka.consume(String.class, String.class)
                .withGroupId("dlq-observer-" + UUID.randomUUID())
                .withAutoCommit()
                .fromTopics(TEMA_DLQ)
                .awaitNoRecords(Duration.ofSeconds(8));

        assertEquals(0, enviados.count(), "un evento ajeno no debe ir a la cola de muertos");
    }
}
