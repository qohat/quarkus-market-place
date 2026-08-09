package com.marketplace.shared.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.UUID;

/**
 * Donde los casos de uso depositan los eventos.
 *
 * <h2>Lo importante es lo que NO hace</h2>
 *
 * No habla con Kafka. Ni siquiera lo conoce. Escribe una fila en la misma base de datos y, por
 * tanto, dentro de <strong>la misma transacción</strong> que el cambio de negocio que la provoca.
 * Esa es toda la magia del patrón: la atomicidad no se consigue coordinando dos sistemas, sino
 * evitando tener que coordinarlos.
 *
 * <pre>
 *   ┌─ transacción ──────────────────────────┐
 *   │  UPDATE stock_item SET reserved = ...   │
 *   │  INSERT INTO outbox_event (...)         │
 *   └──────────── commit atómico ────────────┘
 * </pre>
 *
 * Si algo falla a mitad, se deshacen las dos cosas. Nunca hay un evento de un cambio que no
 * ocurrió, ni un cambio del que nadie se entera.
 *
 * <p>Consecuencia práctica: quien llama a {@link #publish(DomainEvent)} <strong>no está
 * publicando</strong>, está prometiendo publicar. El evento sale de aquí cuando el relay lo
 * recoja, unos segundos después. Ese retardo es el precio del patrón, y es aceptable porque a
 * cambio la entrega deja de poder perderse.
 */
@ApplicationScoped
public class Outbox {

    private final EntityManager entityManager;
    private final ObjectMapper json;

    Outbox(EntityManager entityManager, ObjectMapper json) {
        this.entityManager = entityManager;
        this.json = json;
    }

    /**
     * Anota un evento para su publicación.
     *
     * <p>Debe llamarse dentro de una transacción ya abierta —lo normal es hacerlo desde un caso
     * de uso {@code @Transactional}—. Fuera de una, el evento se guardaría por su cuenta y se
     * perdería la garantía que justifica todo esto.
     *
     * <p>El payload se serializa <em>ahora</em>, no cuando se publique: el evento describe algo
     * que ya ocurrió, y su contenido no puede depender de cómo esté el mundo dentro de diez
     * segundos.
     */
    public void publish(DomainEvent event) {
        var entity = new OutboxEventEntity();
        entity.id = UUID.randomUUID();
        entity.aggregateType = event.aggregateType();
        entity.aggregateId = UUID.fromString(event.aggregateId());
        entity.eventType = event.eventType();
        entity.payload = serialize(event);
        entity.occurredAt = Instant.now();
        entityManager.persist(entity);
    }

    private String serialize(DomainEvent event) {
        try {
            return json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // Un evento que no se puede serializar es un error de programación, no una
            // incidencia: mejor que reviente la transacción de negocio entera a que se
            // confirme un cambio del que nadie podrá enterarse jamás.
            throw new IllegalStateException(
                    "Cannot serialize event " + event.eventType(), e);
        }
    }
}
