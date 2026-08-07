package com.marketplace.catalog.domain;

/**
 * Ciclo de vida de una publicación.
 *
 * <pre>
 *   DRAFT ──publish──> PUBLISHED ──pause──> PAUSED ──publish──> PUBLISHED
 *                          │                                        │
 *                          └──────────────> ARCHIVED <──────────────┘
 * </pre>
 *
 * <p>Poner el comportamiento aquí (y no en un {@code if} disperso por la aplicación) hace que
 * las reglas vivan junto al dato. Es la diferencia entre un enum anémico y uno de dominio.
 */
public enum ListingStatus {

    /** Creada pero aún no visible: el vendedor la está editando. */
    DRAFT,

    /** Visible y comprable. */
    PUBLISHED,

    /** Visible pero temporalmente no comprable (el vendedor la pausó). */
    PAUSED,

    /** Retirada de forma permanente. Estado terminal. */
    ARCHIVED;

    /** ¿Aparece en búsquedas y listados públicos? */
    public boolean isVisibleToBuyers() {
        return this == PUBLISHED || this == PAUSED;
    }

    /** ¿Se pueden crear pedidos o reservas contra esta publicación? */
    public boolean acceptsOrders() {
        return this == PUBLISHED;
    }

    /** ¿Es un estado del que ya no se puede salir? */
    public boolean isTerminal() {
        return this == ARCHIVED;
    }
}
