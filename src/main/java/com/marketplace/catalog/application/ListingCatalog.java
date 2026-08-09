package com.marketplace.catalog.application;

import com.marketplace.catalog.domain.FulfillmentCheck;
import com.marketplace.catalog.domain.Listing;
import com.marketplace.catalog.domain.ListingId;
import com.marketplace.catalog.domain.ListingNotFoundException;
import com.marketplace.catalog.domain.ListingRepository;
import com.marketplace.catalog.domain.ListingStatus;
import com.marketplace.catalog.domain.Listings;
import com.marketplace.catalog.domain.NotTheOwnerException;
import com.marketplace.catalog.domain.ProductListing;
import com.marketplace.catalog.domain.ServiceListing;
import com.marketplace.shared.domain.Money;
import com.marketplace.shared.domain.Page;
import com.marketplace.shared.domain.PageRequest;
import com.marketplace.shared.domain.SellerId;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.ZoneId;
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
 *
 * <h2>Por qué {@code @Transactional} vive aquí y no en el repositorio</h2>
 *
 * Una transacción delimita una <strong>unidad de trabajo del negocio</strong>, y quien sabe
 * dónde empieza y acaba una es esta capa, no el repositorio.
 *
 * <p>Ponerla en cada método del repositorio parece más granular, pero rompe justo cuando
 * importa: una operación que lea, decida y escriba abriría <em>tres</em> transacciones
 * independientes, de modo que un fallo a mitad dejaría persistido lo anterior. Aquí, en cambio,
 * {@code publish()} es atómico de principio a fin: o el listing queda publicado, o no cambia
 * nada.
 *
 * <p>Esto se volverá crítico en el módulo 7: el patrón outbox depende de que escribir el estado
 * y encolar el evento ocurran en <strong>la misma</strong> transacción.
 *
 * <p>Sí, importar {@code jakarta.transaction} mete una anotación de infraestructura en la capa
 * de aplicación. Es un compromiso deliberado y el estándar del sector: decidir los límites
 * transaccionales <em>es</em> responsabilidad de esta capa. El dominio, que es lo que de verdad
 * queremos proteger, sigue intacto.
 *
 * <p>Va a nivel de clase, con lo que también cubre las lecturas. El coste de una transacción de
 * solo lectura es despreciable, y a cambio cada método ve una foto coherente de la base de datos
 * aunque haga varias consultas.
 */
@ApplicationScoped
@Transactional
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

    /**
     * El catálogo público: lo que ve un comprador.
     *
     * <h3>Por qué SE CACHEA esto y no otra cosa</h3>
     *
     * Es la consulta más ejecutada del sistema con diferencia —todo el mundo mira el escaparate,
     * casi nadie publica— y su resultado es el mismo para todos los visitantes. Ese es el perfil
     * exacto de lo que merece caché: <strong>mucha lectura, poca escritura y resultado
     * compartido</strong>.
     *
     * <p>{@code ownedBy} NO se cachea aunque se le parezca: su resultado depende del vendedor, así
     * que habría una entrada por usuario, con una tasa de acierto pésima y consumiendo memoria
     * para servir a una sola persona. Cachear lo que no se comparte es pagar memoria por nada.
     *
     * <h3>La clave es el PageRequest completo</h3>
     *
     * Sin argumentos, Quarkus usaría una clave por defecto y devolvería la misma página para
     * cualquier petición. Con {@code PageRequest} como clave —un record, con equals y hashCode
     * correctos— cada combinación de página y tamaño tiene su entrada. Que sea un record y no
     * dos ints sueltos es lo que hace esto seguro.
     */
    @CacheResult(cacheName = "catalog-browse")
    public Page<Listing> browse(PageRequest pageRequest) {
        return repository.findVisible(pageRequest);
    }

    /**
     * Vacía la caché del catálogo.
     *
     * <h3>Invalidación DIRIGIDA, no por tiempo</h3>
     *
     * La forma habitual de invalidar es poner un tiempo de expiración y aceptar servir datos
     * viejos hasta que venza. Aquí no hace falta: el módulo 7 dejó un evento que dice
     * exactamente cuándo el stock cambió, así que la caché se vacía <strong>cuando hay motivo</strong>.
     *
     * <p>Se invalida el caché ENTERO y no una entrada concreta, y es deliberado: un cambio de
     * stock puede alterar qué publicaciones son visibles y, con ello, el reparto de TODAS las
     * páginas. Invalidar solo la página que contenía esa publicación dejaría las demás
     * descuadradas, que es peor que un fallo de caché.
     *
     * <p>Es el compromiso clásico: {@code @CacheInvalidateAll} es más agresivo pero siempre
     * correcto; la invalidación selectiva rinde más y es fácil equivocarse. Con una caché que se
     * repuebla en una consulta, la correcta gana.
     */
    @CacheInvalidateAll(cacheName = "catalog-browse")
    public void invalidateBrowseCache() {
        // El cuerpo está vacío a propósito: todo el trabajo lo hace el interceptor. Es un método
        // que existe para colgar de él una anotación.
    }

    /** El panel de un vendedor: incluye sus borradores y archivadas. */
    public Page<Listing> ownedBy(SellerId sellerId, PageRequest pageRequest) {
        Objects.requireNonNull(sellerId, "sellerId must not be null");
        return repository.findBySeller(sellerId, pageRequest);
    }

    // ------------------------------------------------------- ciclo de vida

    /*
     * @CacheInvalidateAll va en los métodos PÚBLICOS y no en transitionTo, aunque este sea el
     * único camino real. Dos razones, y la segunda es la que importa:
     *
     * 1. transitionTo es privado, así que ningún interceptor puede envolverlo.
     * 2. Aunque fuera público, llamarlo desde aquí NO pasaría por el interceptor: CDI solo
     *    intercepta las llamadas que entran desde FUERA del bean. Es la misma regla que obligó
     *    a crear TransactionalRunner en el módulo 6 y a sacar `reserve` a su propio método en
     *    la saga del 7. Tercera vez que aparece en el curso, y sigue siendo el error de CDI
     *    que más tiempo cuesta diagnosticar, porque el código parece correcto.
     */
    @CacheInvalidateAll(cacheName = "catalog-browse")
    public Listing publish(ListingId id, SellerId requester) {
        return transitionTo(id, ListingStatus.PUBLISHED, requester);
    }

    @CacheInvalidateAll(cacheName = "catalog-browse")
    public Listing pause(ListingId id, SellerId requester) {
        return transitionTo(id, ListingStatus.PAUSED, requester);
    }

    @CacheInvalidateAll(cacheName = "catalog-browse")
    public Listing archive(ListingId id, SellerId requester) {
        return transitionTo(id, ListingStatus.ARCHIVED, requester);
    }

    /**
     * Aplica una transición de estado y la persiste.
     *
     * <p>La validación de si la transición es legal vive en el dominio ({@code withStatus} lanza
     * al intentar salir de un estado terminal), no aquí. Este método solo coordina: lee, comprueba
     * la propiedad, delega en el dominio y guarda.
     *
     * <p>La comprobación de propiedad está en este método privado, y no repetida en los tres
     * públicos, a propósito: es el <strong>único</strong> camino por el que se cambia el estado de
     * una publicación. Añadir mañana una operación nueva —destacar, renovar— obliga a pasar por
     * aquí, así que nace protegida. Una comprobación copiada tres veces es una comprobación que
     * alguien olvidará la cuarta.
     *
     * <p>El orden importa: primero se resuelve la publicación (404 si no existe) y después la
     * propiedad (403 si no es tuya). Al revés no se puede, porque hasta no cargarla no se sabe de
     * quién es.
     */
    private Listing transitionTo(ListingId id, ListingStatus newStatus, SellerId requester) {
        Objects.requireNonNull(requester, "requester must not be null");
        var listing = byId(id);
        if (!listing.sellerId().equals(requester)) {
            throw new NotTheOwnerException(id, requester);
        }
        var updated = listing.withStatus(newStatus);
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
