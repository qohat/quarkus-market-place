package com.marketplace.catalog.domain;

import com.marketplace.shared.domain.SellerId;

import java.util.List;
import java.util.Optional;

/**
 * Puerto de persistencia del catálogo.
 *
 * <p>Esta interfaz vive en el <strong>dominio</strong>, no en la infraestructura, y ese detalle
 * es todo el punto de la arquitectura de puertos y adaptadores: el dominio declara lo que
 * necesita, y es la infraestructura la que se adapta a esa forma. La dependencia apunta hacia
 * dentro.
 *
 * <p>Fíjate en lo que <em>no</em> hay aquí: ni una anotación de Jakarta, ni de Quarkus, ni de
 * JPA. El vocabulario es de negocio ({@code findVisible}, no {@code selectWhereStatusIn}).
 * Podríamos implementarla con un {@code HashMap}, con PostgreSQL o con una llamada HTTP y el
 * dominio no notaría la diferencia.
 */
public interface ListingRepository {

    /** Guarda una publicación nueva o reemplaza la existente con el mismo id. */
    void save(Listing listing);

    Optional<Listing> findById(ListingId id);

    /** Todas las publicaciones de un vendedor, incluidos borradores. Es su panel de gestión. */
    List<Listing> findBySeller(SellerId sellerId);

    /** Las publicaciones que un comprador puede ver en el catálogo público. */
    List<Listing> findVisible();

    /** @return {@code true} si existía y se borró. */
    boolean deleteById(ListingId id);

    long count();
}
