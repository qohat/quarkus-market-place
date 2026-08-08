package com.marketplace.catalog.domain;

import com.marketplace.shared.domain.SellerId;

/**
 * Alguien intentó modificar una publicación que no es suya.
 *
 * <p>Cubre el hueco que ninguna anotación de seguridad cierra. {@code @RolesAllowed("seller")}
 * comprueba que quien llama es <em>un</em> vendedor; esto comprueba que es <em>el</em> vendedor
 * de esta publicación concreta. El fallo de no hacerlo se llama BOLA (Broken Object Level
 * Authorization) y encabeza el OWASP API Security Top 10, entre otras cosas porque desde fuera
 * una petición así es indistinguible de una legítima.
 *
 * <p>Vive en el dominio, no en la capa REST, por dos motivos. El primero es que es una regla de
 * negocio —quién puede cambiar qué— y no un detalle de transporte. El segundo es práctico: al
 * comprobarse dentro del caso de uso, la regla se aplica también cuando la operación llegue por
 * otro camino, como el consumidor de Kafka del módulo 7. Una comprobación puesta en el recurso
 * REST solo protege la puerta que da a la calle.
 *
 * <p>Como el resto de excepciones de dominio, no sabe nada de HTTP: es un
 * {@code ExceptionMapper} quien decide que esto es un 403.
 */
public class NotTheOwnerException extends RuntimeException {

    private final ListingId listingId;
    private final SellerId requester;

    public NotTheOwnerException(ListingId listingId, SellerId requester) {
        super("Seller " + requester + " does not own listing " + listingId);
        this.listingId = listingId;
        this.requester = requester;
    }

    public ListingId listingId() {
        return listingId;
    }

    public SellerId requester() {
        return requester;
    }
}
