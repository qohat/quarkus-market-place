package com.marketplace.catalog.infrastructure.persistence;

import com.marketplace.catalog.domain.Listing;
import com.marketplace.catalog.domain.ListingId;
import com.marketplace.catalog.domain.ProductListing;
import com.marketplace.shared.domain.SellerId;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

/** Fila de un producto físico: {@code listing_type = 'PRODUCT'}. */
@Entity
@DiscriminatorValue(ProductListingEntity.TYPE)
public class ProductListingEntity extends ListingEntity {

    static final String TYPE = "PRODUCT";

    /*
     * Integer y no int, aunque el dominio use int.
     *
     * En SINGLE_TABLE esta columna es NULL para las filas de servicios, y un int primitivo
     * convertiría ese NULL en 0 al leerlo — un producto agotado y un servicio serían
     * indistinguibles a nivel de campo. Con Integer, el NULL se conserva y el CHECK constraint
     * de la migración garantiza que nunca sea NULL en una fila de tipo PRODUCT.
     */
    @Column(name = "available_stock")
    Integer availableStock;

    protected ProductListingEntity() {
    }

    ProductListingEntity(ProductListing listing) {
        super(listing);
        this.availableStock = listing.availableStock();
    }

    @Override
    public ProductListing toDomain() {
        return new ProductListing(
                new ListingId(id),
                new SellerId(sellerId),
                title,
                money(),
                status,
                availableStock);
    }

    @Override
    public void updateFrom(Listing listing) {
        if (!(listing instanceof ProductListing product)) {
            // Una publicación no cambia de naturaleza: un producto no se convierte en servicio.
            // Si esto salta, es un bug del repositorio, no una entrada inválida del usuario.
            throw new IllegalStateException(
                    "Cannot update a product listing from " + listing.getClass().getSimpleName());
        }
        updateCommonFrom(product);
        this.availableStock = product.availableStock();
    }
}
