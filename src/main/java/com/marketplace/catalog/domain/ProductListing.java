package com.marketplace.catalog.domain;

import com.marketplace.shared.domain.Money;
import com.marketplace.shared.domain.SellerId;

import java.util.Objects;

/**
 * Publicación de un bien físico: se reservan <em>unidades</em> de un stock finito.
 *
 * <p>Su recurso escaso es un contador. En el módulo de inventario esto se traducirá en
 * actualizaciones condicionales del tipo {@code UPDATE ... WHERE stock >= ?}, que es la forma
 * de evitar sobreventa sin bloquear la fila.
 */
public record ProductListing(
        ListingId id,
        SellerId sellerId,
        String title,
        Money price,
        ListingStatus status,
        int availableStock
) implements Listing {

    public ProductListing {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sellerId, "sellerId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(price, "price must not be null");
        Objects.requireNonNull(status, "status must not be null");

        if (title.isBlank()) {
            throw new IllegalArgumentException("Listing title cannot be blank");
        }
        if (!price.isPositive()) {
            throw new IllegalArgumentException("Listing price must be positive, but was " + price);
        }
        if (availableStock < 0) {
            throw new IllegalArgumentException("Stock cannot be negative: " + availableStock);
        }
    }

    /** Crea una publicación nueva en borrador, con id generado. */
    public static ProductListing draft(SellerId sellerId, String title, Money price, int stock) {
        return new ProductListing(
                ListingId.newId(), sellerId, title, price, ListingStatus.DRAFT, stock);
    }

    @Override
    public int availableUnits() {
        return availableStock;
    }

    @Override
    public ProductListing withStatus(ListingStatus newStatus) {
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        if (status.isTerminal() && newStatus != status) {
            throw new IllegalStateException(
                    "Cannot transition out of terminal state " + status);
        }
        return new ProductListing(id, sellerId, title, price, newStatus, availableStock);
    }

    public ProductListing withStock(int newStock) {
        return new ProductListing(id, sellerId, title, price, status, newStock);
    }
}
