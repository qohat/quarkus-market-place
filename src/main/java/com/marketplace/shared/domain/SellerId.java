package com.marketplace.shared.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Identificador tipado de un vendedor.
 *
 * <p>Envolver el UUID en un record en vez de pasear {@code UUID} suelto evita toda una clase
 * de bugs: el compilador ya no deja llamar a {@code find(sellerId)} pasándole por error un
 * {@code buyerId}. El coste en runtime es prácticamente nulo (un objeto de un solo campo).
 */
public record SellerId(UUID value) {

    public SellerId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static SellerId newId() {
        return new SellerId(UUID.randomUUID());
    }

    public static SellerId of(String raw) {
        return new SellerId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
