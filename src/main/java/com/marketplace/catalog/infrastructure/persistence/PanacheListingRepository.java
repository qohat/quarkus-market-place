package com.marketplace.catalog.infrastructure.persistence;

import com.marketplace.catalog.domain.Listing;
import com.marketplace.catalog.domain.ListingId;
import com.marketplace.catalog.domain.ListingRepository;
import com.marketplace.catalog.domain.ListingStatus;
import com.marketplace.shared.domain.Page;
import com.marketplace.shared.domain.PageRequest;
import com.marketplace.shared.domain.SellerId;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistencia con Hibernate ORM y Panache.
 *
 * <p>Implementa dos interfaces a la vez, y cada una aporta algo distinto:
 * <ul>
 *   <li>{@link ListingRepository} — <strong>nuestro puerto</strong>, definido en el dominio.
 *       Es el contrato que ve el resto de la aplicación.</li>
 *   <li>{@link PanacheRepositoryBase} — la ayuda de Quarkus. No tiene métodos que implementar:
 *       Quarkus <em>genera</em> en build time el cuerpo de {@code find}, {@code list},
 *       {@code persist}, {@code count}… inyectando el {@code EntityManager} por debajo. Es la
 *       misma técnica de generación de bytecode del módulo 0, aplicada a la persistencia.</li>
 * </ul>
 *
 * <p>Fuera de este paquete nadie sabe que Panache existe: {@code ListingCatalog} inyecta el
 * puerto, no esta clase.
 */
@ApplicationScoped
public class PanacheListingRepository
        implements ListingRepository, PanacheRepositoryBase<ListingEntity, UUID> {

    /** Los estados que un comprador puede ver en el catálogo. */
    private static final List<ListingStatus> VISIBLE =
            List.of(ListingStatus.PUBLISHED, ListingStatus.PAUSED);

    /**
     * Orden <strong>total</strong>: título y, para desempatar, el id.
     *
     * <p>El desempate no es un detalle estético, es lo que hace correcta la paginación. Con
     * {@code order by title} a secas, dos publicaciones tituladas "Teclado" quedan en un orden
     * que PostgreSQL no garantiza y puede cambiar entre consultas. Consecuencia: al pedir la
     * página 2, un elemento que estaba en la 1 puede reaparecer y otro desaparecer sin haber
     * cambiado nada.
     *
     * <p>Es un bug clásico, difícil de reproducir y que solo se manifiesta con datos reales.
     * <strong>Toda consulta paginada necesita un orden total</strong>, y la forma más simple de
     * garantizarlo es añadir la clave primaria como último criterio.
     */
    private static final String STABLE_ORDER = " order by title, id";

    /** Solo lo necesita {@link #count()}; ver allí el porqué. */
    private final EntityManager entityManager;

    PanacheListingRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Inserta o actualiza.
     *
     * <p>Aquí se ve una de las ideas centrales de JPA: el <strong>dirty checking</strong>. En la
     * rama de actualización no se llama a ningún {@code save}, solo se modifican los campos de
     * una entidad <em>gestionada</em>. Hibernate compara su estado con el que leyó al cargarla y
     * emite el UPDATE al cerrar la transacción, él solo.
     *
     * <p>La consecuencia práctica que sorprende a quien viene de otros stacks: dentro de una
     * transacción, <strong>modificar una entidad cargada ya la persiste</strong>, aunque no
     * llames a nada. Es potente y es una fuente clásica de escrituras accidentales.
     */
    @Override
    public void save(Listing listing) {
        Objects.requireNonNull(listing, "listing must not be null");

        findByIdOptional(listing.id().value()).ifPresentOrElse(
                existing -> existing.updateFrom(listing),
                () -> persist(ListingEntity.fromDomain(listing)));
    }

    @Override
    public Optional<Listing> findById(ListingId id) {
        Objects.requireNonNull(id, "id must not be null");
        return findByIdOptional(id.value()).map(ListingEntity::toDomain);
    }

    /**
     * Publicaciones de un vendedor, incluidos borradores y archivadas.
     *
     * <p>El {@code order by} va en la consulta, no en un {@code sorted()} de Java. Con paginación
     * la diferencia deja de ser una optimización y pasa a ser una cuestión de corrección:
     * ordenar en memoria ordenaría <em>solo la página recibida</em>, no el conjunto, así que el
     * orden global sería aleatorio. Además, ordenar en la base de datos aprovecha el índice
     * {@code listing_seller_idx (seller_id, title)}.
     */
    @Override
    public Page<Listing> findBySeller(SellerId sellerId, PageRequest pageRequest) {
        Objects.requireNonNull(sellerId, "sellerId must not be null");
        Objects.requireNonNull(pageRequest, "pageRequest must not be null");

        return paginate(find("sellerId = ?1" + STABLE_ORDER, sellerId.value()), pageRequest);
    }

    /** El catálogo público. Se apoya en el índice parcial {@code listing_visible_idx}. */
    @Override
    public Page<Listing> findVisible(PageRequest pageRequest) {
        Objects.requireNonNull(pageRequest, "pageRequest must not be null");

        return paginate(find("status in ?1" + STABLE_ORDER, VISIBLE), pageRequest);
    }

    /**
     * Ejecuta la consulta paginada y su recuento.
     *
     * <p>Son <strong>dos</strong> viajes a la base de datos: uno para las filas de la página y
     * otro para el total. No hay forma de evitarlo si quieres dar {@code totalPages}, y ese es
     * justo el argumento de la paginación por keyset, que renuncia al total a cambio de una sola
     * consulta.
     *
     * <p>El {@code count} se pide primero para poder ahorrarse la segunda consulta cuando no hay
     * nada que traer.
     */
    private Page<Listing> paginate(PanacheQuery<ListingEntity> query, PageRequest pageRequest) {
        long total = query.count();
        if (total == 0) {
            return Page.empty(pageRequest);
        }

        List<Listing> items = query
                .page(pageRequest.page(), pageRequest.size())
                .list().stream()
                .map(ListingEntity::toDomain)
                .toList();

        return new Page<>(items, pageRequest.page(), pageRequest.size(), total);
    }

    @Override
    public boolean deleteById(ListingId id) {
        Objects.requireNonNull(id, "id must not be null");
        return deleteById(id.value());
    }

    /**
     * Cuenta todas las publicaciones.
     *
     * <h3>Por qué este método existe y por qué NO delega en Panache</h3>
     *
     * <p><strong>Por qué existe:</strong> nuestro puerto declara {@code count()} como
     * <em>abstracto</em> y {@link PanacheRepositoryBase} lo trae como <em>{@code default}</em>.
     * Cuando un método abstracto y uno {@code default} con la misma firma llegan de dos
     * interfaces sin relación entre sí, Java no escoge por ti: obliga a la clase a declarar
     * explícitamente cuál gana. Sin este método, el código no compila.
     *
     * <p><strong>Por qué no delega:</strong> el primer intento fue
     * {@code PanacheRepositoryBase.super.count()}, y falla en runtime con "this method is
     * normally automatically overridden in subclasses".
     *
     * <p>La razón está en cómo funciona Panache, y es la misma idea del módulo 0: los métodos de
     * {@code PanacheRepositoryBase} son {@code default} vacíos que solo saben lanzar esa
     * excepción, y Quarkus <strong>genera el cuerpo real dentro de tu clase</strong> durante la
     * augmentation. Al declarar {@code count()} a mano, la generación se salta este método —el
     * tuyo manda— y {@code super.count()} acaba invocando el {@code default} original, que es el
     * que explota.
     *
     * <p>La salida es no depender de la generación aquí y usar el {@code EntityManager}
     * directamente. Que sea tan sencillo recuerda lo que Panache realmente es: azúcar sobre JPA,
     * no un sustituto.
     */
    @Override
    public long count() {
        return entityManager
                .createQuery("select count(l) from ListingEntity l", Long.class)
                .getSingleResult();
    }
}
