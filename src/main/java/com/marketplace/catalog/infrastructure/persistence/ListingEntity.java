package com.marketplace.catalog.infrastructure.persistence;

import com.marketplace.catalog.domain.Listing;
import com.marketplace.catalog.domain.ListingStatus;
import com.marketplace.catalog.domain.ProductListing;
import com.marketplace.catalog.domain.ServiceListing;
import com.marketplace.shared.domain.Money;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

/**
 * Entidad JPA de una publicación. <strong>No es el modelo de dominio.</strong>
 *
 * <h2>Por qué existe esta clase en vez de anotar los records de dominio</h2>
 *
 * JPA impone requisitos que son incompatibles con un buen modelo de dominio:
 * <ul>
 *   <li>Constructor sin argumentos y campos mutables, porque Hibernate instancia la entidad
 *       vacía y luego rellena los campos. Un {@code record} inmutable no puede hacer eso.</li>
 *   <li>La clase no puede ser {@code final} y los campos no pueden ser {@code private final},
 *       porque Hibernate genera proxies para la carga perezosa.</li>
 *   <li>Igualdad por identidad de base de datos, no por valor.</li>
 * </ul>
 *
 * Anotar el dominio con JPA obligaría a renunciar a la inmutabilidad, a los compact
 * constructors que garantizan los invariantes y a las sealed interfaces. El dominio dejaría de
 * poder defenderse a sí mismo.
 *
 * <p>El precio de mantenerlos separados es esta traducción explícita. Es un precio real, y a
 * cambio el dominio nunca ve una anotación de Hibernate.
 *
 * <h2>SINGLE_TABLE</h2>
 *
 * Una sola tabla con discriminador. Las lecturas del catálogo no hacen ningún join, que es lo
 * que importa cuando esa consulta se lleva el 95% del tráfico.
 */
@Entity
@Table(name = "listing")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(
        name = "listing_type",
        discriminatorType = DiscriminatorType.STRING,
        length = 16)
public abstract class ListingEntity {

    /*
     * Campos package-private, sin getters ni setters.
     *
     * Esta clase es un detalle de la capa de persistencia: solo la tocan el repositorio y sus
     * subclases, ambos en este paquete. Añadir treinta accesores no aportaría encapsulación
     * alguna, porque no hay nadie fuera a quien encapsular.
     *
     * Hibernate usa acceso por campo (lo deduce de que @Id está sobre un campo), así que no
     * necesita accesores.
     */

    @Id
    UUID id;

    @Column(name = "seller_id", nullable = false)
    UUID sellerId;

    @Column(nullable = false, length = 200)
    String title;

    /*
     * Money se descompone en dos columnas. La alternativa sería un @Embeddable, pero para dos
     * campos añade una clase y una indirección sin ganar nada: la traducción a Money ya ocurre
     * aquí, en un solo sitio.
     */
    @Column(name = "price_amount", nullable = false, precision = 19, scale = 4)
    BigDecimal priceAmount;

    @Column(name = "price_currency", nullable = false, length = 3)
    String priceCurrency;

    /*
     * EnumType.STRING, JAMÁS ORDINAL.
     *
     * Con ORDINAL se guarda la POSICIÓN del valor en el enum: DRAFT=0, PUBLISHED=1... El día
     * que alguien inserte un valor nuevo en medio del enum, o los reordene alfabéticamente,
     * todas las filas de la base de datos cambian de significado en silencio. Es uno de los
     * peores bugs de datos posibles, porque es indetectable hasta que alguien se queja.
     *
     * Con STRING la columna dice 'PUBLISHED' y el orden del enum deja de importar.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    ListingStatus status;

    /** JPA exige un constructor sin argumentos. Protected para que nadie lo use por error. */
    protected ListingEntity() {
    }

    protected ListingEntity(Listing listing) {
        this.id = listing.id().value();
        this.sellerId = listing.sellerId().value();
        this.title = listing.title();
        this.priceAmount = listing.price().amount();
        this.priceCurrency = listing.price().currency().getCurrencyCode();
        this.status = listing.status();
    }

    /** Reconstruye el objeto de dominio, que volverá a validar sus invariantes al construirse. */
    public abstract Listing toDomain();

    /**
     * Traduce de dominio a entidad.
     *
     * <p>Otro {@code switch} exhaustivo sobre la sealed interface: añadir un tercer tipo de
     * publicación rompe la compilación aquí y obliga a decidir cómo se persiste, en vez de
     * dejarlo caer silenciosamente en un caso genérico.
     */
    public static ListingEntity fromDomain(Listing listing) {
        return switch (listing) {
            case ProductListing product -> new ProductListingEntity(product);
            case ServiceListing service -> new ServiceListingEntity(service);
        };
    }

    /** Aplica sobre esta entidad gestionada el estado del objeto de dominio. */
    public abstract void updateFrom(Listing listing);

    protected void updateCommonFrom(Listing listing) {
        this.title = listing.title();
        this.priceAmount = listing.price().amount();
        this.priceCurrency = listing.price().currency().getCurrencyCode();
        this.status = listing.status();
    }

    protected Money money() {
        return new Money(priceAmount, Currency.getInstance(priceCurrency));
    }
}
