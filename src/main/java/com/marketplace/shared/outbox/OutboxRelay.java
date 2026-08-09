package com.marketplace.shared.outbox;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

/**
 * Lleva a Kafka lo que hay en la bandeja de salida.
 *
 * <h2>Por qué duplica, y por qué no se puede evitar</h2>
 *
 * <pre>
 *   1. lee el evento de la tabla
 *   2. lo publica en Kafka        ✓
 *   3. lo marca como publicado    ✗ ← se cae aquí
 * </pre>
 *
 * Al reiniciar vuelve a leerlo y lo publica otra vez. Invertir los pasos 2 y 3 no arregla nada:
 * cambia perder duplicados por perder eventos. Son las dos únicas opciones posibles, y esta
 * elige la menos mala:
 *
 * <pre>
 *   at-most-once   puede perderse, nunca duplica
 *   at-least-once  nunca se pierde, puede duplicar   ← lo que hacemos aquí
 * </pre>
 *
 * <p>«Exactly-once» de extremo a extremo no existe. Kafka tiene una función con ese nombre y es
 * real, pero solo cubre Kafka-a-Kafka dentro de su propio mundo; en cuanto interviene tu base de
 * datos o una pasarela de pago, se acabó. Lo que sí se consigue:
 *
 * <pre>
 *   at-least-once  +  consumidor idempotente  =  effectively once
 * </pre>
 *
 * <h2>Por qué SKIP LOCKED</h2>
 *
 * Con tres réplicas desplegadas, las tres sondean esta tabla. Sin protección, las tres cogerían
 * las mismas filas y publicarían todo por triplicado.
 *
 * <p>{@code FOR UPDATE} a secas —el bloqueo pesimista del módulo 6— haría que las otras dos
 * <strong>esperasen</strong>, convirtiendo tres relays en uno con dos mirando. {@code SKIP LOCKED}
 * dice lo contrario: «no esperes, sáltate lo que otro tiene cogido y llévate lo siguiente». Las
 * tres réplicas se reparten el trabajo solas, sin coordinador y sin duplicar.
 *
 * <p>Es el mismo mecanismo con el que se construye una cola de trabajo sobre una tabla, y merece
 * la pena recordarlo: para muchos sistemas, eso basta y no hace falta Kafka en absoluto.
 */
@ApplicationScoped
public class OutboxRelay {

    private static final Logger LOG = Logger.getLogger(OutboxRelay.class);

    /**
     * Cuántos eventos por pasada. Acotado por la misma razón que el barrido de reservas: una
     * transacción enorme mantiene filas bloqueadas y castiga al resto del sistema. Si hay más
     * pendientes, la siguiente pasada seguirá por donde se quedó.
     */
    private static final int TAMANO_LOTE = 100;

    /**
     * {@code SELECT ... FOR UPDATE SKIP LOCKED} sobre el índice parcial de pendientes.
     *
     * <p>Consulta nativa porque JPA no tiene forma de expresar {@code SKIP LOCKED}:
     * {@code LockModeType.PESSIMISTIC_WRITE} genera {@code FOR UPDATE} y ahí se acaba el
     * vocabulario. Es un caso legítimo de bajar a SQL.
     */
    private static final String PENDIENTES = """
            select * from outbox_event
             where published_at is null
             order by occurred_at
             limit ?1
               for update skip locked
            """;

    private final EntityManager entityManager;
    private final Emitter<Record<String, String>> emitter;

    OutboxRelay(EntityManager entityManager,
                @Channel("events-out") Emitter<Record<String, String>> emitter) {
        this.entityManager = entityManager;
        this.emitter = emitter;
    }

    /**
     * El intervalo fija la latencia máxima entre que algo ocurre y que el resto del sistema se
     * entera. Un segundo es un compromiso razonable: suficientemente rápido para que nadie lo
     * note y suficientemente lento para no castigar la base de datos. Es configurable porque en
     * producción se ajusta según la carga.
     */
    @Scheduled(every = "{marketplace.outbox.poll-interval:1s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void relay() {
        int publicados = publishPending();
        if (publicados > 0) {
            LOG.debugf("Published %d outbox events", publicados);
        }
    }

    /**
     * Publica un lote de eventos pendientes.
     *
     * <p>Todo ocurre en una transacción: si el envío a Kafka falla a mitad, las marcas de
     * publicado se deshacen y la siguiente pasada lo reintenta. Prefiere reenviar a olvidar,
     * que es exactamente la garantía at-least-once que se buscaba.
     *
     * @return cuántos se publicaron
     */
    @Transactional
    public int publishPending() {
        @SuppressWarnings("unchecked")
        List<OutboxEventEntity> pendientes = entityManager
                .createNativeQuery(PENDIENTES, OutboxEventEntity.class)
                .setParameter(1, TAMANO_LOTE)
                .getResultList();

        for (var evento : pendientes) {
            // La CLAVE del registro es el id del agregado, no el del evento. De eso depende que
            // todos los eventos de una misma publicación caigan en la misma partición y lleguen
            // ordenados: con claves distintas, el consumidor podría aplicar «stock = 7» después
            // de «stock = 9» y dejar el catálogo mintiendo de forma permanente.
            emitter.send(Record.of(evento.aggregateId.toString(), evento.payload));
            evento.publishedAt = Instant.now();
        }
        return pendientes.size();
    }

    /**
     * Reenvía TODO, publicado o no. Solo para pruebas.
     *
     * <p>Reproduce a voluntad lo que en producción ocurre por accidente: el relay muere entre
     * publicar y marcar, y al reiniciar vuelve a mandar lo mismo. Es la forma de comprobar que
     * los consumidores aguantan un duplicado, que con at-least-once no es una hipótesis sino una
     * certeza a plazo.
     */
    @Transactional
    public int republishAll() {
        List<OutboxEventEntity> todos = entityManager
                .createQuery("select e from OutboxEventEntity e order by e.occurredAt",
                        OutboxEventEntity.class)
                .getResultList();
        todos.forEach(e -> emitter.send(Record.of(e.aggregateId.toString(), e.payload)));
        return todos.size();
    }
}
