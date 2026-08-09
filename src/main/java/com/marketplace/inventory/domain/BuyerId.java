package com.marketplace.inventory.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Identificador tipado de un comprador.
 *
 * <p>Sale del claim {@code sub} del token, igual que {@code SellerId}. Son tipos distintos a
 * propósito aunque ambos envuelvan un UUID: la misma persona puede ser vendedora y compradora, y
 * mantenerlos separados impide que el compilador acepte pasar uno donde va el otro.
 */
public record BuyerId(UUID value) {

    public BuyerId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static BuyerId of(String raw) {
        return new BuyerId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
