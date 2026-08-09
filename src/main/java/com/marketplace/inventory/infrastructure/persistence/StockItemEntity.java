package com.marketplace.inventory.infrastructure.persistence;

import com.marketplace.catalog.domain.ListingId;
import com.marketplace.inventory.domain.StockItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Fila de {@code stock_item}.
 *
 * <p>Las tres estrategias de reserva comparten esta entidad y esta tabla: lo que cambia entre
 * ellas es <em>cómo</em> se protege la escritura, no qué se guarda. Compartirla es además lo que
 * hace justa la comparación entre las tres.
 */
@Entity
@Table(name = "stock_item")
public class StockItemEntity {

    @Id
    @Column(name = "listing_id", nullable = false)
    UUID listingId;

    @Column(name = "on_hand", nullable = false)
    int onHand;

    @Column(nullable = false)
    int reserved;

    /**
     * Bloqueo optimista. Hibernate incrementa esta columna en cada actualización y añade
     * {@code AND version = ?} al {@code WHERE}: si otra transacción se adelantó, el UPDATE afecta
     * a 0 filas e Hibernate lanza {@code OptimisticLockException}.
     *
     * <p>Existe siempre, aunque solo la use una de las tres estrategias. La atómica la ignora por
     * completo, y ahí está parte de su ventaja: no necesita saber qué versión leyó porque nunca
     * lee.
     */
    @Version
    long version;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    /** JPA exige un constructor sin argumentos. Es la razón de que esto no sea un record. */
    protected StockItemEntity() {
    }

    static StockItemEntity fromDomain(StockItem item) {
        var entity = new StockItemEntity();
        entity.listingId = item.listingId().value();
        entity.onHand = item.onHand();
        entity.reserved = item.reserved();
        entity.updatedAt = Instant.now();
        return entity;
    }

    StockItem toDomain() {
        return new StockItem(new ListingId(listingId), onHand, reserved);
    }

    /**
     * Vuelca un estado de dominio sobre la fila gestionada.
     *
     * <p>No se asignan {@code listingId} ni {@code version}: el primero es la clave y el segundo
     * lo gobierna Hibernate. Escribir la versión a mano rompería precisamente la detección de
     * conflictos que se busca.
     */
    void updateFrom(StockItem item) {
        this.onHand = item.onHand();
        this.reserved = item.reserved();
        this.updatedAt = Instant.now();
    }
}
