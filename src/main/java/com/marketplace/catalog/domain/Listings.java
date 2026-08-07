package com.marketplace.catalog.domain;

import com.marketplace.catalog.domain.FulfillmentCheck.Fulfillable;
import com.marketplace.catalog.domain.FulfillmentCheck.InsufficientAvailability;
import com.marketplace.catalog.domain.FulfillmentCheck.NotAcceptingOrders;

import java.util.Objects;

/**
 * Operaciones de dominio sobre publicaciones que no pertenecen naturalmente a un solo tipo.
 *
 * <p>Es deliberadamente una clase de utilidad sin estado y sin dependencias de Quarkus: se
 * puede testear en microsegundos sin arrancar ningún contenedor de inyección.
 */
public final class Listings {

    private Listings() {
        // clase de utilidad: no se instancia
    }

    /**
     * Comprueba si una publicación puede atender {@code quantity} unidades.
     *
     * <p>Fíjate en el {@code switch}: no tiene rama {@code default}. Como {@link Listing} es
     * {@code sealed}, el compilador sabe que {@link ProductListing} y {@link ServiceListing}
     * agotan las posibilidades. Si mañana añadimos {@code DigitalListing}, este método deja de
     * compilar — y eso es exactamente lo que queremos.
     */
    public static FulfillmentCheck check(Listing listing, int quantity) {
        Objects.requireNonNull(listing, "listing no puede ser null");
        if (quantity < 1) {
            throw new IllegalArgumentException("La cantidad debe ser al menos 1: " + quantity);
        }

        if (!listing.acceptsOrders()) {
            return new NotAcceptingOrders(listing.status());
        }

        // switch sobre patrones de tipo, exhaustivo por ser Listing sealed.
        int available = switch (listing) {
            case ProductListing product -> product.availableStock();
            case ServiceListing service -> service.maxConcurrentBookings();
        };

        if (available < quantity) {
            return new InsufficientAvailability(quantity, available);
        }
        return new Fulfillable(listing.price().times(quantity));
    }

    /**
     * Texto legible del motivo, resolviendo el ADT con <em>record patterns</em>: el
     * {@code case InsufficientAvailability(int requested, int available)} desestructura el
     * record y liga sus componentes a variables en un solo paso.
     */
    public static String describe(FulfillmentCheck result) {
        return switch (result) {
            case Fulfillable(var total) ->
                    "Disponible por un total de " + total;
            case NotAcceptingOrders(var status) ->
                    "La publicación no admite pedidos (estado: " + status + ")";
            case InsufficientAvailability(int requested, int available) ->
                    "Solo quedan %d unidades y se pidieron %d".formatted(available, requested);
        };
    }
}
