package com.marketplace.inventory.domain;

import com.marketplace.catalog.domain.ListingId;

import java.time.Instant;

/**
 * La franja solicitada se solapa con una reserva existente.
 *
 * <p>Es el equivalente de {@link InsufficientStockException} para servicios: el mismo concepto de
 * negocio —«esto ya no está disponible»— sobre un recurso escaso de otra naturaleza. Un producto
 * se agota por cantidad; un servicio, por solapamiento.
 */
public class SlotAlreadyBookedException extends RuntimeException {

    private final ListingId listingId;
    private final Instant from;
    private final Instant to;

    public SlotAlreadyBookedException(ListingId listingId, Instant from, Instant to) {
        super("Slot " + from + " to " + to + " is already booked for listing " + listingId);
        this.listingId = listingId;
        this.from = from;
        this.to = to;
    }

    public ListingId listingId() {
        return listingId;
    }

    public Instant from() {
        return from;
    }

    public Instant to() {
        return to;
    }
}
