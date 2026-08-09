package com.marketplace.inventory.domain;

import com.marketplace.catalog.domain.ListingId;

/**
 * No hay unidades suficientes para atender una reserva.
 *
 * <p>Lleva cuántas se pidieron y cuántas quedaban: un mensaje de «no hay stock» a secas obliga al
 * cliente a otra petición para saber si puede comprar menos.
 *
 * <p>Ojo con interpretar {@code available} como una promesa. Es una foto del instante en que
 * falló la reserva, y para cuando el comprador la lea puede ser otra. Sirve para explicar, no
 * para prometer.
 */
public class InsufficientStockException extends RuntimeException {

    private final ListingId listingId;
    private final int requested;
    private final int available;

    public InsufficientStockException(ListingId listingId, int requested, int available) {
        super("Requested " + requested + " units of " + listingId + " but only "
                + available + " are available");
        this.listingId = listingId;
        this.requested = requested;
        this.available = available;
    }

    public ListingId listingId() {
        return listingId;
    }

    public int requested() {
        return requested;
    }

    public int available() {
        return available;
    }
}
