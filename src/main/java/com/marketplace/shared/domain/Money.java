package com.marketplace.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Value Object que representa una cantidad de dinero en una moneda concreta.
 *
 * <p>Decisiones de diseño relevantes:
 * <ul>
 *   <li>La escala se normaliza a los decimales oficiales de la moneda (EUR -> 2, JPY -> 0)
 *       dentro del <em>compact constructor</em>. Sin esto, {@code equals} heredado del record
 *       compararía {@code 34.5} y {@code 34.50} como distintos, porque
 *       {@link BigDecimal#equals} tiene en cuenta la escala.</li>
 *   <li>Se usa {@link RoundingMode#UNNECESSARY}: si alguien construye un importe con más
 *       decimales de los que la moneda admite, salta {@link ArithmeticException}. Preferimos
 *       fallar en el borde del sistema antes que redondear dinero en silencio.</li>
 *   <li>Es inmutable: toda operación devuelve una instancia nueva.</li>
 * </ul>
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(amount, "amount no puede ser null");
        Objects.requireNonNull(currency, "currency no puede ser null");
        // Reasignar un componente en el compact constructor es legal y normaliza el valor
        // ANTES de que se asigne al campo final del record.
        amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money zero(String currencyCode) {
        return of("0", currencyCode);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money times(int factor) {
        if (factor < 0) {
            throw new IllegalArgumentException("El factor no puede ser negativo: " + factor);
        }
        return new Money(amount.multiply(BigDecimal.valueOf(factor)), currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /**
     * Ordena dos importes de la misma moneda. Comparar monedas distintas no tiene un orden
     * bien definido sin un tipo de cambio, así que lanzamos en vez de inventar una respuesta.
     */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other no puede ser null");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Monedas incompatibles: %s vs %s".formatted(
                            currency.getCurrencyCode(), other.currency.getCurrencyCode()));
        }
    }
}
