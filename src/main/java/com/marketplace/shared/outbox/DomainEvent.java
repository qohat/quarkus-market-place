package com.marketplace.shared.outbox;

/**
 * Lo que un evento tiene que saber decir de sí mismo para poder salir por el outbox.
 *
 * <p>Vive en {@code shared} porque cualquier contexto necesita publicar, y es una interfaz mínima
 * a propósito: nada de fechas de emisión ni identificadores técnicos, que son responsabilidad de
 * la infraestructura. Un evento de dominio describe <strong>algo que ya ocurrió</strong> —de ahí
 * los nombres en pasado— y sus datos son los del hecho, no los del transporte.
 */
public interface DomainEvent {

    /**
     * Qué clase de cosa cambió: {@code "stock"}, {@code "reservation"}. Decide el tema de Kafka.
     */
    String aggregateType();

    /**
     * Identificador de la cosa que cambió. Se usa como CLAVE DE PARTICIÓN, así que de él depende
     * que los eventos de un mismo agregado lleguen ordenados al consumidor.
     */
    String aggregateId();

    /** Qué pasó, en pasado: {@code "StockChanged"}, {@code "ReservationExpired"}. */
    default String eventType() {
        return getClass().getSimpleName();
    }
}
