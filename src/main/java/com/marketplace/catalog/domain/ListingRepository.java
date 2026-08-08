package com.marketplace.catalog.domain;

import com.marketplace.shared.domain.Page;
import com.marketplace.shared.domain.PageRequest;
import com.marketplace.shared.domain.SellerId;

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

    /**
     * Publicaciones de un vendedor, incluidos borradores. Es su panel de gestión.
     *
     * <p>Solo existe la versión paginada, a propósito. Un {@code findAll()} sin límite es una
     * bomba de relojería: funciona perfectamente durante meses y revienta el día que un vendedor
     * acumula cien mil publicaciones. Si el método no existe, nadie puede llamarlo por descuido.
     */
    Page<Listing> findBySeller(SellerId sellerId, PageRequest pageRequest);

    /** Las publicaciones que un comprador puede ver en el catálogo público. */
    Page<Listing> findVisible(PageRequest pageRequest);

    /** @return {@code true} si existía y se borró. */
    boolean deleteById(ListingId id);

    long count();
}
