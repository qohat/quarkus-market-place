package com.marketplace.shared.outbox;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Fila de {@code outbox_event}. */
@Entity
@Table(name = "outbox_event")
public class OutboxEventEntity extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    public String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    public UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    public String eventType;

    /**
     * {@code JdbcTypeCode(SqlTypes.JSON)} le dice a Hibernate que esta columna es JSONB y no una
     * cadena cualquiera. Sin ello, PostgreSQL rechaza la inserción por incompatibilidad de tipos:
     * es estricto con JSONB, y con razón — valida que el contenido sea JSON al escribirlo.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    public String payload;

    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;

    /** {@code null} mientras esté pendiente de publicar. */
    @Column(name = "published_at")
    public Instant publishedAt;
}
