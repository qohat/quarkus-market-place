package com.marketplace.catalog.infrastructure.rest;

import com.marketplace.catalog.domain.ServiceListing;

/**
 * Campos que solo tienen sentido para un servicio reservable.
 *
 * <p>Al agruparlos en un sub-objeto en lugar de aplanarlos en {@link ListingResponse}, el JSON de
 * un producto no arrastra un puñado de campos a {@code null}. El cliente ve una estructura que
 * refleja la realidad: si hay bloque {@code service}, es un servicio.
 *
 * <p>{@code slotMinutes} en vez de un {@code Duration} serializado: ISO-8601 ({@code "PT1H"}) es
 * correcto pero incómodo de consumir. Un entero de minutos no admite ambigüedad.
 */
public record ServiceDetails(long slotMinutes, String timeZone) {

    public static ServiceDetails from(ServiceListing listing) {
        return new ServiceDetails(
                listing.slotDuration().toMinutes(),
                listing.timeZone().getId());
    }
}
