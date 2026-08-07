package com.marketplace.catalog.domain;

import java.util.Objects;
import java.util.UUID;

/** Identificador tipado de una publicación del catálogo. Ver {@code SellerId} para el porqué. */
public record ListingId(UUID value) {

    public ListingId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static ListingId newId() {
        return new ListingId(UUID.randomUUID());
    }

    public static ListingId of(String raw) {
        return new ListingId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
