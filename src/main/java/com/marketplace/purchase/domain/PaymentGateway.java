package com.marketplace.purchase.domain;

import com.marketplace.shared.domain.Money;

/**
 * La pasarela de pago: un sistema EXTERNO.
 *
 * <p>Es el puerto que hace inevitable la saga. Un cobro no participa en la transacción de
 * PostgreSQL, así que no hay {@code @Transactional} capaz de abarcar «reservar stock y cobrar».
 * Si el cobro sale bien y luego falla algo, no se puede hacer rollback de un cargo a una tarjeta:
 * hay que <strong>compensar</strong> emitiendo un reembolso, que es un hecho nuevo y no la
 * anulación del anterior.
 *
 * <p>Esa asimetría es la diferencia esencial entre una transacción y una saga.
 */
public interface PaymentGateway {

    /**
     * Cobra un importe.
     *
     * @param idempotencyKey clave que la pasarela usa para no cobrar dos veces lo mismo. Es la
     *                       pieza que hace seguro reintentar: si la red se cae después de que el
     *                       cargo se procese pero antes de recibir la respuesta, el reintento con
     *                       la misma clave devuelve el resultado original en lugar de cobrar otra
     *                       vez. Todas las pasarelas reales lo soportan, y usarlo es obligatorio.
     * @throws PaymentDeclinedException si el pago se rechaza
     */
    String charge(String idempotencyKey, Money amount);

    /** Compensación de {@link #charge}. No anula el cargo: emite un movimiento contrario. */
    void refund(String chargeId);
}
