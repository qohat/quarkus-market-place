package com.marketplace.purchase.domain;

/**
 * El pago fue rechazado.
 *
 * <p>No es un fallo técnico sino un resultado de negocio perfectamente normal —fondos
 * insuficientes, tarjeta caducada—, y por eso NO debe reintentarse: reintentar un rechazo solo
 * gasta cuota en la pasarela y puede disparar sus controles antifraude.
 *
 * <p>Distinguir esto de un error de red es la decisión que hace correcta o incorrecta una
 * política de reintentos, y es el tema del módulo 8.
 */
public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException(String reason) {
        super("Payment declined: " + reason);
    }
}
