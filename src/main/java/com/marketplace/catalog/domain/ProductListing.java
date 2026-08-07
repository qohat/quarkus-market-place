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
        Objects.requireNonNull(id, "id no puede ser null");
        Objects.requireNonNull(sellerId, "sellerId no puede ser null");
        Objects.requireNonNull(title, "title no puede ser null");
        Objects.requireNonNull(price, "price no puede ser null");
        Objects.requireNonNull(status, "status no puede ser null");

        if (title.isBlank()) {
            throw new IllegalArgumentException("El título no puede estar vacío");
        }
        if (!price.isPositive()) {
            throw new IllegalArgumentException("El precio debe ser positivo, pero era " + price);
        }
        if (availableStock < 0) {
            throw new IllegalArgumentException("El stock no puede ser negativo: " + availableStock);
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
        Objects.requireNonNull(newStatus, "newStatus no puede ser null");
        if (status.isTerminal() && newStatus != status) {
            throw new IllegalStateException(
                    "No se puede salir del estado terminal " + status);
        }
        return new ProductListing(id, sellerId, title, price, newStatus, availableStock);
    }

    public ProductListing withStock(int newStock) {
        return new ProductListing(id, sellerId, title, price, status, newStock);
    }
}
