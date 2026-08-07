package com.marketplace.catalog.infrastructure.rest;

import com.marketplace.catalog.domain.ListingNotFoundException;
import com.marketplace.shared.infrastructure.rest.ProblemDetail;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Traduce la excepción de dominio a un 404.
 *
 * <p>Aquí es donde se paga la decisión de que {@link ListingNotFoundException} no supiera nada
 * de HTTP: el dominio se limita a decir "esto no existe", y es <strong>cada adaptador</strong>
 * quien decide qué significa eso en su protocolo. En HTTP es un 404; el consumidor de Kafka del
 * módulo 7 lo tratará como mensaje a descartar o a enviar a la cola de errores, sin que ninguna
 * de las dos decisiones contamine a la otra.
 *
 * <p>{@code @Provider} es lo que registra la clase. ARC la descubre en build time por el índice
 * Jandex — sin escaneo de classpath al arrancar.
 */
@Provider
public class ListingNotFoundExceptionMapper implements ExceptionMapper<ListingNotFoundException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(ListingNotFoundException exception) {
        return ProblemDetail.type("listing-not-found")
                .title("Listing not found")
                .status(Response.Status.NOT_FOUND)
                .detail("No listing exists with id " + exception.listingId())
                .instance(uriInfo.getPath())
                .build()
                .toResponse();
    }
}
