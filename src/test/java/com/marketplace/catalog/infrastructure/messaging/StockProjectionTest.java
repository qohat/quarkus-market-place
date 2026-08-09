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
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * El flujo completo: PostgreSQL → outbox → relay → Kafka → catálogo.
 *
 * <p>Cierra el cabo suelto del módulo 6. Allí se decidió que el catálogo guardara una copia de
 * {@code availableStock} para el escaparate y que la sincronizara un evento; hasta ahora ese
 * evento no existía y la copia se quedaba congelada en el valor inicial.
 *
 * <h2>Por qué este test usa espera activa y los demás no</h2>
 *
 * Es el único punto del proyecto donde la asincronía es real: entre que Inventario confirma y el
 * catálogo se entera hay una vuelta por Kafka que ningún truco puede volver síncrona. Esperar a
 * que el efecto ocurra —con un tiempo límite— es la única forma honesta de probarlo.
 *
 * <p>La alternativa, un {@code Thread.sleep(2000)}, sería lenta cuando funciona y engañosa cuando
 * falla. {@code await()} devuelve en cuanto la condición se cumple y falla con un mensaje claro si
 * no lo hace nunca.
 */
@QuarkusTest
@DisplayName("Proyección de stock en el catálogo")
class StockProjectionTest {

    private static final BuyerId COMPRADOR = new BuyerId(UUID.randomUUID());

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

    /*
     * Limpiar también AL TERMINAR, no solo antes.
     *
     * Estos tests necesitan transacciones reales, así que no pueden usar @TestTransaction ni
     * apoyarse en su rollback: lo que escriben, queda. Los tests más antiguos —los que cuentan
     * filas con @TestTransaction— asumen una base vacía, así que estos residuos los hacían
     * fallar a distancia, en otra clase y por un motivo que no aparecía por ningún lado.
     *
     * Regla general: quien no puede deshacer lo que escribe, recoge al salir.
     */
    @AfterEach
    void tearDown() {
        database.clear();
    }


    private int stockEnElCatalogo(com.marketplace.catalog.domain.ListingId id) {
        return tx.call(() -> ((ProductListing) catalog.byId(id)).availableStock());
    }

    @Test
    @DisplayName("una reserva acaba reflejándose en el escaparate")
    void reservationEventuallyReachesTheCatalogue() {
        var seller = SellerId.newId();
        var listing = tx.call(() ->
                catalog.createProduct(seller, "Teclado", Money.of("25.00", "EUR"), 10));

        tx.run(() -> inventory.track(listing.id(), 10));
        tx.run(() -> inventory.reserve(listing.id(), COMPRADOR, 3));

        // El planificador está apagado en test, así que el relay se dispara a mano: así el
        // test controla CUÁNDO salen los eventos, y lo único asíncrono que queda es la vuelta
        // por Kafka.
        tx.run(() -> relay.publishPending());

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertEquals(7, stockEnElCatalogo(listing.id())));
    }

    @Test
    @DisplayName("el ciclo entero deja el escaparate cuadrado")
    void fullPurchaseCycleKeepsTheProjectionCorrect() {
        var seller = SellerId.newId();
        var listing = tx.call(() ->
                catalog.createProduct(seller, "Teclado", Money.of("25.00", "EUR"), 10));

        tx.run(() -> inventory.track(listing.id(), 10));
        var reserva = tx.call(() -> inventory.reserve(listing.id(), COMPRADOR, 4));
        tx.run(() -> inventory.confirm(reserva.id()));
        tx.run(() -> relay.publishPending());

        // Reservar bajó disponible a 6; confirmar no lo cambia —ya estaba descontado— y solo
        // saca las unidades del almacén. El escaparate acaba en 6, que es lo que un comprador
        // puede llevarse ahora.
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertEquals(6, stockEnElCatalogo(listing.id())));
    }

    @Test
    @DisplayName("procesar el mismo evento dos veces deja el mismo resultado")
    void reprocessingTheSameEventIsHarmless() {
        var seller = SellerId.newId();
        var listing = tx.call(() ->
                catalog.createProduct(seller, "Teclado", Money.of("25.00", "EUR"), 10));

        tx.run(() -> inventory.track(listing.id(), 10));
        tx.run(() -> inventory.reserve(listing.id(), COMPRADOR, 3));
        tx.run(() -> relay.publishPending());
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertEquals(7, stockEnElCatalogo(listing.id())));

        // El outbox garantiza at-least-once, así que un duplicado ocurrirá antes o después: el
        // relay puede morir entre publicar y marcar. Reenviamos el mismo evento a propósito.
        //
        // No hace falta llevar registro de mensajes vistos porque StockChanged transporta el
        // ESTADO RESULTANTE y no un incremento: aplicarlo dos veces deja el mismo número. Con
        // un delta («se reservaron 3»), esta segunda entrega descontaría tres unidades de más.
        tx.run(() -> relay.republishAll());

        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertEquals(7, stockEnElCatalogo(listing.id())));
    }
}
