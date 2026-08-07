package com.marketplace.catalog.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.marketplace.catalog.domain.FulfillmentCheck;
import com.marketplace.catalog.domain.FulfillmentCheck.Fulfillable;
import com.marketplace.catalog.domain.FulfillmentCheck.InsufficientAvailability;
import com.marketplace.catalog.domain.FulfillmentCheck.NotAcceptingOrders;

/**
 * Respuesta de la comprobación de disponibilidad.
 *
 * <p>Aquí se ve para qué servía modelar {@link FulfillmentCheck} como ADT en lugar de un
 * booleano: el motivo del rechazo llega al cliente con sus datos, y el {@code switch} que lo
 * traduce es exhaustivo. Si añadiéramos un cuarto resultado posible, el compilador nos obligaría
 * a decidir cómo se le cuenta al cliente.
 *
 * <p>{@code reason} es un código estable ({@code INSUFFICIENT_AVAILABILITY}), no un texto para
 * humanos. Los clientes deben poder ramificar sobre él sin parsear prosa, y traducirlo al idioma
 * del usuario es responsabilidad de la interfaz.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AvailabilityResponse(
        boolean fulfillable,
        String reason,
        MoneyView total,
        Integer available
) {

    public static AvailabilityResponse from(FulfillmentCheck check) {
        return switch (check) {
            case Fulfillable(var total) ->
                    new AvailabilityResponse(true, null, MoneyView.from(total), null);

            case NotAcceptingOrders(var status) ->
                    new AvailabilityResponse(false, "NOT_ACCEPTING_ORDERS_" + status, null, null);

            case InsufficientAvailability(int requested, int available) ->
                    new AvailabilityResponse(false, "INSUFFICIENT_AVAILABILITY", null, available);
        };
    }
}
