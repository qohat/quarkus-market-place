package com.marketplace.inventory.domain;

import java.util.Objects;
import java.util.UUID;

/** Identificador tipado de una reserva. Mismo criterio que {@code ListingId} y {@code SellerId}. */
public record ReservationId(UUID value) {

    public ReservationId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static ReservationId of(String raw) {
        return new ReservationId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
