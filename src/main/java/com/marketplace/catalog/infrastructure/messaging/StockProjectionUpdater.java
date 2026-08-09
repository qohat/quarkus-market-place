package com.marketplace.catalog.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.inventory.domain.event.StockChanged;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * Mantiene al día la copia de stock que el catálogo enseña en el escaparate.
 *
 * <h2>Consistencia eventual, dicha en voz alta</h2>
 *
 * Entre que Inventario confirma una venta y este método se ejecuta pasan unos segundos. Durante
 * esa ventana, el catálogo enseña un número antiguo. <strong>Es aceptable y es el diseño</strong>:
 * un número mostrado siempre está atrasado, y nadie decide nada con él — reservar va contra
 * Inventario, que sí es la verdad.
 *
 * <h2>Por qué {@code @Blocking}</h2>
 *
 * Los consumidores de Reactive Messaging corren sobre un event loop de Vert.x, y este método hace
 * JDBC, que bloquea. Sin la anotación sería el bug número 1 del módulo 4 en su forma más pura: un
 * consumidor de Kafka parando los event loops de toda la aplicación. Y es de los sitios donde más
 * se cuela, porque un consumidor no «parece» un endpoint y nadie se pregunta en qué hilo corre.
 *
 * <h2>Idempotencia sin llevar registro</h2>
 *
 * El outbox garantiza at-least-once, así que aquí <strong>van a llegar duplicados</strong>. No
 * hace falta una tabla de mensajes vistos: {@link StockChanged} lleva el estado resultante y no un
 * incremento, así que aplicarlo dos veces deja el mismo valor.
 *
 * <h2>Las dos clases de mensaje que no se pueden procesar</h2>
 *
 * <pre>
 *   «no es para mí»        →  otro tipo de evento en el tema compartido  →  IGNORAR
 *   «es mío y está roto»   →  payload corrupto, datos imposibles         →  DEJAR FALLAR → DLQ
 * </pre>
 *
 * Confundirlas tiene consecuencias caras en las dos direcciones. Si se ignora todo, un fallo real
 * desaparece en silencio y el catálogo se desincroniza sin que nadie se entere. Si se deja fallar
 * todo, el primer evento ajeno bloquea la partición: con estrategia de reintento, <strong>un
 * mensaje envenenado detiene para siempre todo lo que venga detrás</strong>.
 */
@ApplicationScoped
public class StockProjectionUpdater {

    private static final Logger LOG = Logger.getLogger(StockProjectionUpdater.class);

    private final EntityManager entityManager;
    private final ObjectMapper json;
    private final Counter aplicados;
    private final Counter ignorados;

    StockProjectionUpdater(EntityManager entityManager, ObjectMapper json, MeterRegistry registry) {
        this.entityManager = entityManager;
        this.json = json;
        // Etiquetas de cardinalidad acotada: dos valores. El listingId NO va aquí — sería una
        // serie temporal por publicación y haría explotar Prometheus. Los identificadores van
        // en las trazas, que sí están pensadas para alta cardinalidad.
        this.aplicados = Counter.builder("marketplace.projection.events")
                .tag("outcome", "applied").register(registry);
        this.ignorados = Counter.builder("marketplace.projection.events")
                .tag("outcome", "ignored").register(registry);
    }

    /**
     * {@code @WithSpan} crea un tramo propio dentro de la traza.
     *
     * <p>Es lo que hace visible el salto asíncrono: el contexto de traza viaja en las cabeceras
     * del mensaje de Kafka, así que este tramo aparece <strong>colgando de la petición HTTP que
     * originó la compra</strong>, aunque se ejecute segundos después y en otro proceso. Sin él,
     * la traza terminaría al publicar y el trabajo del consumidor quedaría huérfano.
     */
    @Incoming("events-in")
    @Blocking
    @Transactional
    @WithSpan("catalog.apply-stock-changed")
    public void onEvent(String payload) {
        StockChanged evento;
        try {
            var nodo = json.readTree(payload);

            // COMPROBACIÓN EXPLÍCITA DE FORMA, y no «deserializar y ver si falla».
            //
            // Esto costó dos tests rojos: Quarkus desactiva FAIL_ON_UNKNOWN_PROPERTIES en su
            // ObjectMapper, así que readValue() acepta CUALQUIER JSON y devuelve un StockChanged
            // con los campos a nulo. Un evento de otro consumidor no fallaba al deserializar:
            // pasaba el filtro y reventaba después, acabando en la cola de muertos sin motivo.
            //
            // Una DLQ llena de mensajes sanos ajenos es peor que no tenerla: se vuelve ruido que
            // nadie revisa, y el día que llegue un problema de verdad pasará inadvertido.
            if (!nodo.hasNonNull("listingId") || !nodo.has("available")) {
                ignorados.increment();
                LOG.debugf("Ignored event that is not a StockChanged: %s", payload);
                return;
            }
            evento = json.treeToValue(nodo, StockChanged.class);
        } catch (com.fasterxml.jackson.core.JacksonException noEsJson) {
            ignorados.increment();
            LOG.debugf("Ignored non-JSON message: %s", noEsJson.getMessage());
            return;
        }

        // Es nuestro, pero puede venir roto. A partir de aquí, cualquier fallo se DEJA PROPAGAR:
        // la estrategia dead-letter-queue lo aparta a marketplace-events-dlq con la causa en sus
        // cabeceras, y el consumidor sigue atendiendo el resto. Tragarse esto en silencio sería
        // perder datos sin dejar rastro.
        UUID listingId = UUID.fromString(evento.listingId());

        // Actualización directa por SQL: es una proyección, no una decisión de negocio. No hay
        // invariante que comprobar ni estado previo que considerar, solo un número que se
        // sobrescribe — que es justo lo que la hace idempotente.
        int filas = entityManager.createQuery("""
                        update ProductListingEntity l
                           set l.availableStock = :available
                         where l.id = :id
                        """)
                .setParameter("available", evento.available())
                .setParameter("id", listingId)
                .executeUpdate();

        aplicados.increment();

        if (filas == 0) {
            // Legítimo: un servicio reservable no tiene stock, y una publicación borrada tampoco.
            // No es motivo para reintentar ni para mandar el mensaje a la cola de muertos.
            LOG.debugf("No product listing to update for %s", evento.listingId());
        }
    }
}
