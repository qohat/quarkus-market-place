package com.marketplace.catalog.infrastructure.rest;

import com.marketplace.shared.domain.Money;

/**
 * Representación de un importe en el JSON de la API.
 *
 * <p><strong>{@code amount} es un String, no un número.</strong> Si se serializara como número
 * JSON, cualquier cliente JavaScript que hiciera {@code JSON.parse} lo convertiría a un
 * {@code double} IEEE-754: {@code 25.00} se leería como {@code 25} (adiós a la escala) y los
 * importes con muchos decimales acumularían error. Es el bug clásico de las APIs de pago
 * consumidas desde el navegador.
 *
 * <p>Como String, el importe llega intacto y el cliente decide si lo pasa por una librería de
 * decimales o lo muestra tal cual.
 *
 * <p>La moneda viaja siempre pegada al importe: un número suelto sin moneda no es dinero.
 */
public record MoneyView(String amount, String currency) {

    public static MoneyView from(Money money) {
        return new MoneyView(money.amount().toPlainString(), money.currency().getCurrencyCode());
    }

    /** Convierte de vuelta al dominio, aplicando sus validaciones (escala, moneda válida). */
    public Money toDomain() {
        return Money.of(amount, currency);
    }
}
