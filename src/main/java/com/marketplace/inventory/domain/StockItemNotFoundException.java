package com.marketplace.inventory.domain;

import com.marketplace.catalog.domain.ListingId;

/**
 * La publicación no tiene existencias registradas en Inventario.
 *
 * <p>Es distinto de «no hay stock»: significa que este contexto no sabe nada de esa publicación.
 * Ocurre con los servicios reservables, que no se miden en unidades, y también sería la señal de
 * que catálogo e inventario se han desincronizado.
 */
public class StockItemNotFoundException extends RuntimeException {

    private final ListingId listingId;

    public StockItemNotFoundException(ListingId listingId) {
        super("No stock is tracked for listing " + listingId);
        this.listingId = listingId;
    }

    public ListingId listingId() {
        return listingId;
    }
}
