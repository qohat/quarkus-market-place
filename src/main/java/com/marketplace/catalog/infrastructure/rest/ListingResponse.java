package com.marketplace.catalog.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.marketplace.catalog.domain.Listing;
import com.marketplace.catalog.domain.ProductListing;
import com.marketplace.catalog.domain.ServiceListing;

/**
 * Lo que la API devuelve por cada publicación.
 *
 * <p>Es un DTO, no una entidad de dominio. Esa separación tiene un precio (este fichero) y una
 * recompensa: el JSON público deja de estar acoplado al modelo interno. Podemos renombrar un
 * campo del dominio, partir {@code Listing} en dos o cambiar de motor de persistencia sin
 * romper a ningún cliente. Y el dominio se mantiene libre de anotaciones de Jackson.
 *
 * <p>El polimorfismo se resuelve con un <strong>discriminador plano</strong>: un campo
 * {@code type} y, para los servicios, un sub-objeto {@code service}. Un único esquema, fácil de
 * consumir desde cualquier lenguaje.
 *
 * <p>{@code @JsonInclude(NON_NULL)} omite {@code service} en los productos, en lugar de emitir
 * {@code "service": null}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListingResponse(
        String id,
        String type,
        String sellerId,
        String title,
        MoneyView price,
        String status,
        int availableUnits,
        ServiceDetails service
) {

    public static final String TYPE_PRODUCT = "PRODUCT";
    public static final String TYPE_SERVICE = "SERVICE";

    /**
     * Traduce del dominio a la API.
     *
     * <p>Aquí es donde el {@code sealed} del módulo 1 se paga solo: el {@code switch} es
     * exhaustivo sin rama {@code default}. Si mañana añadimos un tercer tipo de publicación,
     * este método deja de compilar y nos obliga a decidir cómo se representa en el JSON — en
     * vez de devolver silenciosamente un objeto incompleto al cliente.
     */
    public static ListingResponse from(Listing listing) {
        return switch (listing) {
            case ProductListing product -> new ListingResponse(
                    product.id().toString(),
                    TYPE_PRODUCT,
                    product.sellerId().toString(),
                    product.title(),
                    MoneyView.from(product.price()),
                    product.status().name(),
                    product.availableStock(),
                    null);

            case ServiceListing service -> new ListingResponse(
                    service.id().toString(),
                    TYPE_SERVICE,
                    service.sellerId().toString(),
                    service.title(),
                    MoneyView.from(service.price()),
                    service.status().name(),
                    service.maxConcurrentBookings(),
                    ServiceDetails.from(service));
        };
    }
}
