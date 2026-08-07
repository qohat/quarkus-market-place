package com.marketplace.catalog.domain;

/**
 * La publicación solicitada no existe.
 *
 * <p>Es una excepción <em>de dominio</em>: no sabe nada de HTTP ni de códigos de estado. En el
 * paso 2.6 un {@code ExceptionMapper} la traducirá a un 404, y en el módulo 7 el consumidor de
 * Kafka la tratará de otra forma. Cada adaptador decide qué significa en su protocolo.
 *
 * <p>Es <strong>unchecked</strong> a propósito. Una checked exception obligaría a propagar
 * {@code throws} por toda la aplicación por algo que la capa intermedia no puede resolver, y
 * además rompería el uso de estos métodos dentro de lambdas y streams.
 */
public class ListingNotFoundException extends RuntimeException {

    private final ListingId listingId;

    public ListingNotFoundException(ListingId listingId) {
        super("No listing exists with id " + listingId);
        this.listingId = listingId;
    }

    public ListingId listingId() {
        return listingId;
    }
}
