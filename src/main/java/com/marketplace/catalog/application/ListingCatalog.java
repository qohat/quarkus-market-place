package com.marketplace.catalog.application;

import com.marketplace.catalog.domain.FulfillmentCheck;
import com.marketplace.catalog.domain.Listing;
import com.marketplace.catalog.domain.ListingId;
import com.marketplace.catalog.domain.ListingNotFoundException;
import com.marketplace.catalog.domain.ListingRepository;
import com.marketplace.catalog.domain.ListingStatus;
import com.marketplace.catalog.domain.Listings;
import com.marketplace.catalog.domain.ProductListing;
import com.marketplace.catalog.domain.ServiceListing;
import com.marketplace.shared.domain.Money;
import com.marketplace.shared.domain.SellerId;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * Casos de uso del catálogo.
 *
 * <p>Es la capa que orquesta el negocio, entre el adaptador de entrada (REST, y más adelante un
 * consumidor de Kafka) y el puerto de salida ({@link ListingRepository}). Su vocabulario es el
 * del negocio —publicar, pausar, archivar— y no conoce HTTP: no devuelve códigos de estado ni
 * {@code Response}, solo objetos de dominio y excepciones de dominio.
 *
 * <p>Esa independencia es lo que permitirá que la misma operación se dispare desde un endpoint
 * REST y desde un mensaje de Kafka sin duplicar una línea de lógica.
 */
@ApplicationScoped
public class ListingCatalog {

    private final ListingRepository repository;

    /**
     * Inyección por constructor.
     *
     * <p>No lleva {@code @Inject}: al haber un único constructor, ARC lo detecta solo. Es
     * package-private porque nadie fuera del paquete necesita construirlo a mano, salvo los
     * tests, que están en el mismo paquete.
     *
     * <p>Con el campo {@code final}, la dependencia es obligatoria y el objeto queda inmutable.
     * Y como no hay magia de campos, la clase se instancia con {@code new} en un test sin
     * necesidad de ningún contenedor.
     */
    ListingCatalog(ListingRepository repository) {
        this.repository = repository;
    }

    // ---------------------------------------------------------------- creación

    /** Crea un producto en borrador. Aún no es visible para los compradores. */
    public ProductListing createProduct(SellerId sellerId, String title, Money price, int stock) {
        var listing = ProductListing.draft(sellerId, title, price, stock);
        repository.save(listing);
        return listing;
    }

    /** Crea un servicio reservable en borrador, con una plaza por franja. */
    public ServiceListing createService(
            SellerId sellerId, String title, Money price, Duration slotDuration, ZoneId timeZone) {
        var listing = ServiceListing.draft(sellerId, title, price, slotDuration, timeZone);
        repository.save(listing);
        return listing;
    }

    // ---------------------------------------------------------------- consulta

    /**
     * @throws ListingNotFoundException si no existe
     */
    public Listing byId(ListingId id) {
        Objects.requireNonNull(id, "id must not be null");
        return repository.findById(id).orElseThrow(() -> new ListingNotFoundException(id));
    }

    /** El catálogo público: lo que ve un comprador. */
    public List<Listing> browse() {
        return repository.findVisible();
    }

    /** El panel de un vendedor: incluye sus borradores y archivadas. */
    public List<Listing> ownedBy(SellerId sellerId) {
        Objects.requireNonNull(sellerId, "sellerId must not be null");
        return repository.findBySeller(sellerId);
    }

    // ------------------------------------------------------- ciclo de vida

    public Listing publish(ListingId id) {
        return transitionTo(id, ListingStatus.PUBLISHED);
    }

    public Listing pause(ListingId id) {
        return transitionTo(id, ListingStatus.PAUSED);
    }

    public Listing archive(ListingId id) {
        return transitionTo(id, ListingStatus.ARCHIVED);
    }

    /**
     * Aplica una transición de estado y la persiste.
     *
     * <p>La validación de si la transición es legal vive en el dominio ({@code withStatus} lanza
     * al intentar salir de un estado terminal), no aquí. Este método solo coordina: lee, delega
     * en el dominio y guarda.
     */
    private Listing transitionTo(ListingId id, ListingStatus newStatus) {
        var updated = byId(id).withStatus(newStatus);
        repository.save(updated);
        return updated;
    }

    // ---------------------------------------------------------- disponibilidad

    /**
     * Comprueba si una publicación puede atender {@code quantity} unidades o plazas.
     *
     * @throws ListingNotFoundException si no existe
     */
    public FulfillmentCheck checkAvailability(ListingId id, int quantity) {
        return Listings.check(byId(id), quantity);
    }
}
