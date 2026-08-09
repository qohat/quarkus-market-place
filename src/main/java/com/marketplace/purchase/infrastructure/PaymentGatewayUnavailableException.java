package com.marketplace.purchase.infrastructure;

/**
 * Fallo TÉCNICO de la pasarela: red, 503, conexión rechazada.
 *
 * <p>Existe para poder distinguirlo de {@code PaymentDeclinedException}, y esa distinción es la
 * decisión más importante de toda política de reintentos:
 *
 * <pre>
 *   esto                       →  transitorio  →  reintentar TIENE sentido
 *   PaymentDeclinedException   →  permanente   →  reintentar NO tiene sentido
 * </pre>
 *
 * <p>Reintentar un rechazo no solo es inútil —fallará igual las veces que se intente— sino que
 * gasta cuota en la pasarela y puede disparar sus controles antifraude. Es de los errores que se
 * pagan en dinero.
 *
 * <p>Vive en infraestructura y no en el dominio a propósito: es un accidente del transporte, no
 * un concepto de negocio. Al dominio le da igual por qué no se pudo cobrar.
 */
public class PaymentGatewayUnavailableException extends RuntimeException {

    public PaymentGatewayUnavailableException(String reason) {
        super("Payment gateway unavailable: " + reason);
    }
}
