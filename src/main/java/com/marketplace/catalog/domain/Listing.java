package com.marketplace.catalog.domain;

import com.marketplace.shared.domain.Money;
import com.marketplace.shared.domain.SellerId;

/**
 * Una publicación del marketplace. Existe en exactamente dos sabores, declarados aquí mismo
 * con {@code permits}.
 *
 * <p><strong>Por qué {@code sealed}:</strong> el compilador conoce la lista cerrada de
 * implementaciones, así que un {@code switch} sobre un {@code Listing} que cubra
 * {@link ProductListing} y {@link ServiceListing} es <em>exhaustivo</em> y no necesita
 * {@code default}. El día que añadamos un tercer tipo, el build romperá en todos los sitios
 * que haya que revisar — en vez de que la rama {@code default} se lo trague en silencio y
 * lo descubramos en producción.
 *
 * <p>Es la traducción a Java de lo que en Scala harías con un {@code sealed trait} y en
 * Kotlin con una {@code sealed class}.
 */
public sealed interface Listing permits ProductListing, ServiceListing {

    ListingId id();

    SellerId sellerId();

    String title();

    /** Precio por unidad (producto) o por franja reservada (servicio). */
    Money price();

    ListingStatus status();

    /**
     * Devuelve una copia con otro estado.
     *
     * <p>Las implementaciones estrechan el tipo de retorno ({@code ProductListing} devuelve
     * {@code ProductListing}), aprovechando los tipos de retorno covariantes de Java. Así el
     * llamante no pierde información de tipo al cambiar de estado.
     */
    Listing withStatus(ListingStatus newStatus);

    /** Cuántas unidades/plazas se pueden comprometer ahora mismo. */
    int availableUnits();

    default boolean acceptsOrders() {
        return status().acceptsOrders();
    }

    default boolean isVisibleToBuyers() {
        return status().isVisibleToBuyers();
    }
}
