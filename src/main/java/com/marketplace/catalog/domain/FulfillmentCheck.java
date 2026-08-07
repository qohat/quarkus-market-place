package com.marketplace.catalog.domain;

import com.marketplace.shared.domain.Money;

/**
 * Resultado de comprobar si una publicación puede atender una cantidad pedida.
 *
 * <p>Esto es un <strong>ADT</strong> (tipo algebraico de datos): en vez de devolver un
 * {@code boolean} que pierde el porqué, o de lanzar excepciones para controlar el flujo,
 * modelamos las tres respuestas posibles como tipos. Es el equivalente Java de lo que en
 * Scala escribirías como {@code sealed trait} con case classes.
 *
 * <p>Ventaja concreta: quien consume el resultado hace un {@code switch} exhaustivo y el
 * compilador le obliga a decidir qué hacer en cada caso. El mensaje de error para el usuario
 * sale del propio tipo, no de un {@code if} adivinando la causa.
 */
public sealed interface FulfillmentCheck {

    /** Se puede servir. Lleva el importe total ya calculado. */
    record Fulfillable(Money total) implements FulfillmentCheck {}

    /** La publicación existe pero su estado no admite pedidos (borrador, pausada, archivada). */
    record NotAcceptingOrders(ListingStatus status) implements FulfillmentCheck {}

    /** Hay menos disponibilidad de la pedida. */
    record InsufficientAvailability(int requested, int available) implements FulfillmentCheck {}

    default boolean isFulfillable() {
        return this instanceof Fulfillable;
    }
}
