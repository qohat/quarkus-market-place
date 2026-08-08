package com.marketplace.catalog.application;

import com.marketplace.catalog.domain.FulfillmentCheck;
import com.marketplace.catalog.domain.ListingId;
import com.marketplace.catalog.domain.ListingNotFoundException;
import com.marketplace.catalog.domain.ListingStatus;
import com.marketplace.catalog.domain.NotTheOwnerException;
import com.marketplace.catalog.infrastructure.InMemoryListingRepository;
import com.marketplace.shared.domain.Money;
import com.marketplace.shared.domain.PageRequest;
import com.marketplace.shared.domain.SellerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test de los casos de uso <strong>sin arrancar Quarkus</strong>.
 *
 * <p>Esto es lo que compra la inyección por constructor: {@code new ListingCatalog(...)} y ya
 * está. No hace falta {@code @QuarkusTest}, ni {@code @InjectMock}, ni un contenedor CDI. Y
 * como el adaptador en memoria es una implementación real del puerto, tampoco hacen falta
 * mocks: el test ejercita la integración real entre caso de uso y repositorio.
 */
@DisplayName("ListingCatalog")
class ListingCatalogTest {

    private static final Money PRICE = Money.of("25.00", "EUR");

    private ListingCatalog catalog;
    private SellerId alice;

    @BeforeEach
    void setUp() {
        catalog = new ListingCatalog(new InMemoryListingRepository());
        alice = SellerId.newId();
    }

    @Nested
    @DisplayName("creación")
    class Creation {

        @Test
        @DisplayName("un producto nuevo nace en borrador y no es visible")
        void newProductStartsAsDraft() {
            var product = catalog.createProduct(alice, "Teclado mecánico", PRICE, 10);

            assertEquals(ListingStatus.DRAFT, product.status());
            assertEquals(10, product.availableStock());
            assertTrue(catalog.browse(PageRequest.first()).items().isEmpty(), "un borrador no debe aparecer en el catálogo");
        }

        @Test
        @DisplayName("un servicio nuevo nace en borrador con una plaza por franja")
        void newServiceStartsAsDraft() {
            var service = catalog.createService(
                    alice, "Clase de guitarra", PRICE,
                    Duration.ofMinutes(60), ZoneId.of("Europe/Madrid"));

            assertEquals(ListingStatus.DRAFT, service.status());
            assertEquals(1, service.maxConcurrentBookings());
            assertEquals(Duration.ofMinutes(60), service.slotDuration());
        }

        @Test
        @DisplayName("lo creado queda persistido y se recupera por id")
        void createdListingIsPersisted() {
            var product = catalog.createProduct(alice, "Teclado", PRICE, 10);

            assertEquals(product, catalog.byId(product.id()));
        }
    }

    @Nested
    @DisplayName("ciclo de vida")
    class Lifecycle {

        @Test
        @DisplayName("publicar hace visible la publicación")
        void publishingMakesItVisible() {
            var product = catalog.createProduct(alice, "Teclado", PRICE, 10);

            var published = catalog.publish(product.id(), alice);

            assertEquals(ListingStatus.PUBLISHED, published.status());
            assertEquals(List.of(published), catalog.browse(PageRequest.first()).items());
        }

        @Test
        @DisplayName("pausar la mantiene visible pero deja de admitir pedidos")
        void pausingKeepsItVisible() {
            var product = catalog.createProduct(alice, "Teclado", PRICE, 10);
            catalog.publish(product.id(), alice);

            var paused = catalog.pause(product.id(), alice);

            assertEquals(ListingStatus.PAUSED, paused.status());
            assertEquals(1, catalog.browse(PageRequest.first()).items().size());
            assertInstanceOf(
                    FulfillmentCheck.NotAcceptingOrders.class,
                    catalog.checkAvailability(product.id(), 1));
        }

        @Test
        @DisplayName("archivar la retira del catálogo")
        void archivingRemovesItFromBrowse() {
            var product = catalog.createProduct(alice, "Teclado", PRICE, 10);
            catalog.publish(product.id(), alice);

            catalog.archive(product.id(), alice);

            assertTrue(catalog.browse(PageRequest.first()).items().isEmpty());
        }

        @Test
        @DisplayName("la transición ilegal la rechaza el dominio, no el caso de uso")
        void illegalTransitionIsRejectedByTheDomain() {
            var product = catalog.createProduct(alice, "Teclado", PRICE, 10);
            catalog.archive(product.id(), alice);

            assertThrows(IllegalStateException.class, () -> catalog.publish(product.id(), alice));
        }
    }

    /**
     * El fallo que encabeza el OWASP API Security Top 10: autenticarse correctamente y operar
     * sobre un recurso ajeno. La comprobación vive en el caso de uso, así que estos tests corren
     * sin Quarkus, sin token y sin Keycloak: son reglas de negocio, no de transporte.
     */
    @Nested
    @DisplayName("propiedad del recurso")
    class Ownership {

        @Test
        @DisplayName("otro vendedor no puede publicar lo que no es suyo")
        void anotherSellerCannotPublish() {
            var rival = SellerId.newId();
            var product = catalog.createProduct(alice, "Teclado", PRICE, 10);

            var exception = assertThrows(
                    NotTheOwnerException.class, () -> catalog.publish(product.id(), rival));

            assertEquals(product.id(), exception.listingId());
            assertEquals(rival, exception.requester());
        }

        @Test
        @DisplayName("otro vendedor no puede pausar ni archivar lo que no es suyo")
        void anotherSellerCannotPauseOrArchive() {
            var rival = SellerId.newId();
            var product = catalog.createProduct(alice, "Teclado", PRICE, 10);
            catalog.publish(product.id(), alice);

            assertThrows(NotTheOwnerException.class, () -> catalog.pause(product.id(), rival));
            assertThrows(NotTheOwnerException.class, () -> catalog.archive(product.id(), rival));
        }

        @Test
        @DisplayName("la publicación ajena queda intacta tras el intento")
        void deniedAttemptLeavesTheListingUntouched() {
            var rival = SellerId.newId();
            var product = catalog.createProduct(alice, "Teclado", PRICE, 10);
            catalog.publish(product.id(), alice);

            assertThrows(NotTheOwnerException.class, () -> catalog.archive(product.id(), rival));

            // Lo importante no es que lance, sino que no haya escrito nada antes de lanzar.
            assertEquals(ListingStatus.PUBLISHED, catalog.byId(product.id()).status());
        }

        @Test
        @DisplayName("una publicación inexistente da 'no existe', no 'no es tuya'")
        void missingListingReportsNotFoundRatherThanOwnership() {
            // El orden de las comprobaciones importa: si se resolviera al revés, un atacante
            // distinguiría los ids que existen de los que no por el tipo de error.
            assertThrows(
                    ListingNotFoundException.class,
                    () -> catalog.archive(ListingId.newId(), SellerId.newId()));
        }
    }

    @Nested
    @DisplayName("consulta")
    class Queries {

        @Test
        @DisplayName("byId lanza si no existe")
        void byIdThrowsWhenMissing() {
            var missing = ListingId.newId();

            var exception =
                    assertThrows(ListingNotFoundException.class, () -> catalog.byId(missing));
            assertEquals(missing, exception.listingId());
        }

        @Test
        @DisplayName("ownedBy devuelve también los borradores del vendedor")
        void ownedByIncludesDrafts() {
            var bob = SellerId.newId();
            catalog.createProduct(alice, "Teclado", PRICE, 1);
            catalog.createProduct(alice, "Ratón", PRICE, 1);
            catalog.createProduct(bob, "Monitor", PRICE, 1);

            assertEquals(2, catalog.ownedBy(alice, PageRequest.first()).items().size());
            assertEquals(1, catalog.ownedBy(bob, PageRequest.first()).items().size());
            assertTrue(catalog.browse(PageRequest.first()).items().isEmpty(), "ninguna está publicada todavía");
        }
    }

    @Nested
    @DisplayName("disponibilidad")
    class Availability {

        @Test
        @DisplayName("calcula el total cuando hay stock suficiente")
        void computesTotalWhenAvailable() {
            var product = catalog.createProduct(alice, "Teclado", PRICE, 10);
            catalog.publish(product.id(), alice);

            var result = catalog.checkAvailability(product.id(), 3);

            var fulfillable = assertInstanceOf(FulfillmentCheck.Fulfillable.class, result);
            assertEquals(Money.of("75.00", "EUR"), fulfillable.total());
        }

        @Test
        @DisplayName("informa de cuánto queda cuando no alcanza")
        void reportsAvailabilityWhenInsufficient() {
            var product = catalog.createProduct(alice, "Teclado", PRICE, 2);
            catalog.publish(product.id(), alice);

            var result = catalog.checkAvailability(product.id(), 5);

            var insufficient =
                    assertInstanceOf(FulfillmentCheck.InsufficientAvailability.class, result);
            assertEquals(5, insufficient.requested());
            assertEquals(2, insufficient.available());
        }

        @Test
        @DisplayName("lanza si la publicación no existe")
        void throwsWhenListingMissing() {
            assertThrows(
                    ListingNotFoundException.class,
                    () -> catalog.checkAvailability(ListingId.newId(), 1));
        }
    }
}
