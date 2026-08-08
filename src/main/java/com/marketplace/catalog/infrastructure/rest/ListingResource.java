package com.marketplace.catalog.infrastructure.rest;

import com.marketplace.catalog.application.ListingCatalog;
import com.marketplace.catalog.domain.Listing;
import com.marketplace.catalog.domain.ListingId;
import com.marketplace.shared.domain.Money;
import com.marketplace.shared.domain.Page;
import com.marketplace.shared.domain.PageRequest;
import com.marketplace.shared.domain.SellerId;
import com.marketplace.shared.infrastructure.rest.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

/**
 * Adaptador HTTP del catálogo.
 *
 * <p>Su única responsabilidad es traducir entre HTTP y el dominio: parsear la petición, delegar
 * en {@link ListingCatalog} y convertir el resultado en un DTO con su código de estado. No hay
 * ni una regla de negocio aquí — si la hubiera, el consumidor de Kafka del módulo 7 tendría que
 * duplicarla.
 *
 * <p><strong>Detalle de scope importante:</strong> en Quarkus REST los recursos son
 * <em>singleton</em> por defecto, no {@code @RequestScoped} como en JAX-RS clásico. Se instancia
 * uno solo y lo comparten todas las peticiones, lo que ahorra una asignación por petición. La
 * contrapartida es la de siempre: <strong>nada de estado mutable en campos</strong>. Aquí solo
 * hay una referencia {@code final} al caso de uso.
 */
/*
 * @Produces sí va en la clase: todo lo que devolvemos es JSON.
 *
 * @Consumes NO va aquí, aunque sea tentador. A nivel de clase se aplicaría también a
 * publish/pause/archive, que son POST sin cuerpo y por tanto llegan sin cabecera Content-Type:
 * JAX-RS los rechazaría con 415 Unsupported Media Type. @Consumes describe lo que acepta un
 * método concreto, no una política de la clase, así que va solo donde hay cuerpo que leer.
 */
@Path("/listings")
@Produces(MediaType.APPLICATION_JSON)
public class ListingResource {

    /** Formato de UUID, para validar identificadores antes de intentar parsearlos. */
    private static final String UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private final ListingCatalog catalog;

    /**
     * {@code UriInfo} sí es información por petición, pero se puede inyectar en un singleton
     * porque lo que se inyecta es un proxy que resuelve la petición en curso.
     */
    @Context
    UriInfo uriInfo;

    ListingResource(ListingCatalog catalog) {
        this.catalog = catalog;
    }

    // ------------------------------------------------------------------ lectura

    /**
     * Catálogo público, o el panel de un vendedor si se pasa {@code ?seller=}.
     *
     * <p>Devolver el DTO directamente, en lugar de envolverlo en un {@code Response}, deja que
     * Quarkus REST resuelva el 200 y la serialización. Se usa {@code Response} solo cuando hay
     * que controlar el código de estado o las cabeceras.
     *
     * <p>Los parámetros de paginación tienen <strong>valores por defecto</strong>: un cliente que
     * no sepa nada de páginas recibe las primeras 20 y no se lleva la base de datos entera. El
     * comportamiento seguro es el que se obtiene sin hacer nada, que es como deben diseñarse los
     * valores por defecto.
     */
    @GET
    public PageResponse<ListingResponse> list(
            @QueryParam("seller") String sellerId,

            @QueryParam("page") @DefaultValue("0")
            @PositiveOrZero(message = "page cannot be negative")
            int page,

            /*
             * El máximo se valida DOS veces: aquí con @Max, para que un size excesivo devuelva un
             * 400 claro con el resto de errores de validación, y otra vez dentro de PageRequest,
             * que es quien de verdad garantiza el invariante para cualquier otro camino de
             * entrada (Kafka, un job programado, otro endpoint futuro).
             *
             * No es duplicar una regla de negocio: es que el borde da un buen mensaje y el
             * dominio da la garantía.
             */
            @QueryParam("size") @DefaultValue("20")
            @Positive(message = "size must be greater than zero")
            @Max(value = PageRequest.MAX_SIZE, message = "size cannot exceed 100")
            int size) {

        var pageRequest = PageRequest.of(page, size);

        Page<Listing> resultado = (sellerId == null || sellerId.isBlank())
                ? catalog.browse(pageRequest)
                : catalog.ownedBy(parseSellerId(sellerId), pageRequest);

        return PageResponse.from(resultado, ListingResponse::from);
    }

    @GET
    @Path("{id}")
    public ListingResponse byId(
            @PathParam("id")
            @Pattern(regexp = UUID_PATTERN, message = "id must be a UUID")
            String id) {

        return ListingResponse.from(catalog.byId(parseListingId(id)));
    }

    /**
     * Disponibilidad para una cantidad concreta. Por defecto, una unidad.
     *
     * <p>{@code @Positive} sobre el parámetro de consulta sustituye a la comprobación manual: una
     * cantidad de cero o negativa se rechaza con 400 antes de que el dominio llegue a verla.
     */
    @GET
    @Path("{id}/availability")
    public AvailabilityResponse availability(
            @PathParam("id")
            @Pattern(regexp = UUID_PATTERN, message = "id must be a UUID")
            String id,

            @QueryParam("quantity") @DefaultValue("1")
            @Positive(message = "quantity must be greater than zero")
            int quantity) {

        return AvailabilityResponse.from(catalog.checkAvailability(parseListingId(id), quantity));
    }

    // ----------------------------------------------------------------- creación

    /**
     * Crea un producto físico.
     *
     * <p>Responde <strong>201 Created</strong> con cabecera {@code Location} apuntando al recurso
     * recién creado. Es lo que distingue una API bien educada: el cliente no tiene que componer
     * la URL a mano ni asumir cómo se construyen los identificadores.
     *
     * <p>El vendedor llega por cabecera de forma provisional. En el módulo 5 saldrá del token
     * OIDC, que es el único sitio del que puede salir sin abrir un agujero: si el cliente pudiera
     * elegirlo, cualquiera publicaría en nombre de otro.
     */
    @POST
    @Path("products")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createProduct(
            @HeaderParam("X-Seller-Id")
            @NotBlank(message = "X-Seller-Id header is required")
            @Pattern(regexp = UUID_PATTERN, message = "X-Seller-Id must be a UUID")
            String sellerId,

            /*
             * @Valid es lo que dispara la validación del cuerpo. Sin él, las anotaciones del
             * record se ignorarían en silencio: Bean Validation valida en cascada solo donde
             * se le pide explícitamente.
             */
            @Valid CreateProductRequest request) {

        var listing = catalog.createProduct(
                parseSellerId(sellerId),
                request.title(),
                parseMoney(request.amount(), request.currency()),
                request.stock());

        return created(listing);
    }

    /** Crea un servicio reservable. */
    @POST
    @Path("services")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createService(
            @HeaderParam("X-Seller-Id")
            @NotBlank(message = "X-Seller-Id header is required")
            @Pattern(regexp = UUID_PATTERN, message = "X-Seller-Id must be a UUID")
            String sellerId,

            @Valid CreateServiceRequest request) {

        var listing = catalog.createService(
                parseSellerId(sellerId),
                request.title(),
                parseMoney(request.amount(), request.currency()),
                Duration.ofMinutes(request.slotMinutes()),
                parseZone(request.timeZone()));

        return created(listing);
    }

    // ------------------------------------------------------------ ciclo de vida

    @POST
    @Path("{id}/publish")
    public ListingResponse publish(@PathParam("id") String id) {
        return ListingResponse.from(catalog.publish(parseListingId(id)));
    }

    @POST
    @Path("{id}/pause")
    public ListingResponse pause(@PathParam("id") String id) {
        return ListingResponse.from(catalog.pause(parseListingId(id)));
    }

    @POST
    @Path("{id}/archive")
    public ListingResponse archive(@PathParam("id") String id) {
        return ListingResponse.from(catalog.archive(parseListingId(id)));
    }

    // ---------------------------------------------------------------- auxiliares

    private Response created(Listing listing) {
        var location = uriInfo.getBaseUriBuilder()
                .path("listings")
                .path(listing.id().toString())
                .build();

        return Response.created(location)
                .entity(ListingResponse.from(listing))
                .build();
    }

    /*
     * Conversión pura: el formato ya lo garantizó Bean Validation antes de llegar aquí, así
     * que estos dos no necesitan defenderse.
     */

    private SellerId parseSellerId(String raw) {
        return SellerId.of(raw);
    }

    private ListingId parseListingId(String raw) {
        return ListingId.of(raw);
    }

    /*
     * Estos dos SÍ necesitan traducir el error, y la razón es justo la distinción del paso:
     * Bean Validation comprueba la FORMA ("XYZ" son tres mayúsculas, "Europe/Atlantis" tiene
     * pinta de zona IANA), pero no puede comprobar la EXISTENCIA. Que la moneda esté en ISO
     * 4217 o la zona en la base de datos IANA solo lo sabe la biblioteca estándar, al
     * intentar construirlas.
     *
     * Replicar aquí esos catálogos sería absurdo y quedaría desactualizado. La regla es la de
     * siempre: valida declarativamente lo que es sintaxis, y deja que falle donde vive el
     * conocimiento.
     *
     * Lo que sí hacemos es reescribir el mensaje: Currency.getInstance("XYZ") lanza una
     * excepción cuyo texto es, literalmente, "XYZ". Inútil para quien recibe el 400.
     */

    private Money parseMoney(String amount, String currency) {
        try {
            return Money.of(amount, currency);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Cannot interpret '%s %s' as an amount: %s"
                            .formatted(amount, currency, e.getMessage()));
        }
    }

    private ZoneId parseZone(String raw) {
        try {
            return ZoneId.of(raw);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("'%s' is not a known IANA time zone".formatted(raw));
        }
    }
}
