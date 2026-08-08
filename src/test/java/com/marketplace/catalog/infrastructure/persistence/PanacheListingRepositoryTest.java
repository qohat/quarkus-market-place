package com.marketplace.catalog.infrastructure.persistence;

import com.marketplace.catalog.domain.ListingId;
import com.marketplace.catalog.domain.ListingRepository;
import com.marketplace.catalog.domain.ListingStatus;
import com.marketplace.catalog.domain.ProductListing;
import com.marketplace.catalog.domain.ServiceListing;
import com.marketplace.shared.domain.Money;
import com.marketplace.shared.domain.PageRequest;
import com.marketplace.shared.domain.SellerId;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistencia real contra PostgreSQL, levantado por Dev Services.
 *
 * <p>Cada test lleva {@code @TestTransaction}: Quarkus lo envuelve en una transacción y hace
 * <strong>rollback al terminar</strong>. Los tests quedan aislados sin necesidad de limpiar a
 * mano y sin depender del orden de ejecución. Funciona aquí —y no en los tests de REST— porque
 * el test y el repositorio comparten el mismo hilo y la misma transacción.
 */
@QuarkusTest
@DisplayName("PanacheListingRepository")
class PanacheListingRepositoryTest {

    /** Inyectamos el PUERTO. Que detrás haya Panache es justo lo que el resto no debe saber. */
    @Inject
    ListingRepository repository;

    /** Solo para asomarnos al SQL crudo y comprobar cómo quedaron las columnas. */
    @Inject
    EntityManager entityManager;

    private static final Money PRICE = Money.of("25.00", "EUR");

    @Test
    @DisplayName("ARC resuelve el puerto a la implementación de Panache")
    void portResolvesToPanacheAdapter() {
        String injected = repository.getClass().getName();

        assertTrue(injected.contains("PanacheListingRepository"),
                "se esperaba el adaptador de Panache, llegó: " + injected);
        // Sigue siendo un client proxy: es @ApplicationScoped, como en el módulo 2.
        assertTrue(injected.contains("ClientProxy"), injected);
    }


        @Test
        @TestTransaction
        @DisplayName("un producto sobrevive intacto a la base de datos")
        void productRoundTrips() {
            var original = ProductListing
                    .draft(SellerId.newId(), "Teclado mecánico", PRICE, 40)
                    .withStatus(ListingStatus.PUBLISHED);

            repository.save(original);
            entityManager.flush();
            entityManager.clear();   // vacía la caché de primer nivel: fuerza leer de la BD

            var recuperado = repository.findById(original.id()).orElseThrow();

            // Igualdad de record: compara todos los componentes, incluido Money.
            assertEquals(original, recuperado);
            assertInstanceOf(ProductListing.class, recuperado);
        }

        @Test
        @TestTransaction
        @DisplayName("un servicio conserva duración y zona horaria")
        void serviceRoundTrips() {
            var original = ServiceListing.draft(
                    SellerId.newId(), "Clase de guitarra", PRICE,
                    Duration.ofMinutes(90), ZoneId.of("Europe/Madrid"));

            repository.save(original);
            entityManager.flush();
            entityManager.clear();

            var recuperado = assertInstanceOf(
                    ServiceListing.class, repository.findById(original.id()).orElseThrow());

            assertEquals(original, recuperado);
            assertEquals(Duration.ofMinutes(90), recuperado.slotDuration());
            assertEquals(ZoneId.of("Europe/Madrid"), recuperado.timeZone());
        }

        @Test
        @TestTransaction
        @DisplayName("el importe conserva la escala pese a la columna NUMERIC(19,4)")
        void moneyKeepsItsScale() {
            var original = ProductListing.draft(SellerId.newId(), "Teclado", PRICE, 1);

            repository.save(original);
            entityManager.flush();
            entityManager.clear();

            var recuperado = repository.findById(original.id()).orElseThrow();

            // La columna guarda 25.0000; Money renormaliza a los 2 decimales del euro.
            assertEquals(Money.of("25.00", "EUR"), recuperado.price());
            assertEquals(2, recuperado.price().amount().scale());
        }


        @Test
        @TestTransaction
        @DisplayName("ambos tipos comparten tabla, distinguidos por el discriminador")
        void bothTypesShareOneTable() {
            var seller = SellerId.newId();
            repository.save(ProductListing.draft(seller, "Teclado", PRICE, 10));
            repository.save(ServiceListing.draft(
                    seller, "Clase", PRICE, Duration.ofMinutes(60), ZoneId.of("Europe/Madrid")));
            entityManager.flush();

            @SuppressWarnings("unchecked")
            List<Object[]> filas = entityManager
                    .createNativeQuery(
                            "select listing_type, available_stock, slot_minutes from listing "
                                    + "order by listing_type")
                    .getResultList();

            assertEquals(2, filas.size());

            // Fila PRODUCT: tiene stock, no tiene campos de calendario.
            assertEquals("PRODUCT", filas.get(0)[0]);
            assertEquals(10, ((Number) filas.get(0)[1]).intValue());
            assertEquals(null, filas.get(0)[2]);

            // Fila SERVICE: al revés. Esta asimetría es lo que los CHECK constraints protegen.
            assertEquals("SERVICE", filas.get(1)[0]);
            assertEquals(null, filas.get(1)[1]);
            assertEquals(60, ((Number) filas.get(1)[2]).intValue());
        }

        @Test
        @TestTransaction
        @DisplayName("el estado se guarda como texto, no como número")
        void statusIsStoredAsText() {
            var listing = ProductListing.draft(SellerId.newId(), "Teclado", PRICE, 1)
                    .withStatus(ListingStatus.PUBLISHED);
            repository.save(listing);
            entityManager.flush();

            var valor = entityManager
                    .createNativeQuery("select status from listing where id = :id")
                    .setParameter("id", listing.id().value())
                    .getSingleResult();

            // Con EnumType.ORDINAL aquí habría un 1, y reordenar el enum cambiaría
            // el significado de todas las filas existentes sin que nada fallara.
            assertEquals("PUBLISHED", valor);
        }


        @Test
        @TestTransaction
        @DisplayName("findVisible excluye borradores y archivadas")
        void findsOnlyVisible() {
            var seller = SellerId.newId();
            repository.save(ProductListing.draft(seller, "Borrador", PRICE, 1));
            repository.save(ProductListing.draft(seller, "Publicado", PRICE, 1)
                    .withStatus(ListingStatus.PUBLISHED));
            repository.save(ProductListing.draft(seller, "Pausado", PRICE, 1)
                    .withStatus(ListingStatus.PAUSED));
            repository.save(ProductListing.draft(seller, "Archivado", PRICE, 1)
                    .withStatus(ListingStatus.ARCHIVED));
            entityManager.flush();

            var visibles = repository.findVisible(PageRequest.first()).items().stream().map(l -> l.title()).toList();

            // Ordenado por la base de datos, no en Java.
            assertEquals(List.of("Pausado", "Publicado"), visibles);
        }

        @Test
        @TestTransaction
        @DisplayName("findBySeller aísla a cada vendedor")
        void isolatesSellers() {
            var alice = SellerId.newId();
            var bob = SellerId.newId();
            repository.save(ProductListing.draft(alice, "Teclado", PRICE, 1));
            repository.save(ProductListing.draft(alice, "Ratón", PRICE, 1));
            repository.save(ProductListing.draft(bob, "Monitor", PRICE, 1));
            entityManager.flush();

            assertEquals(
                    List.of("Ratón", "Teclado"),
                    repository.findBySeller(alice, PageRequest.first()).items().stream().map(l -> l.title()).toList());
            assertEquals(1, repository.findBySeller(bob, PageRequest.first()).items().size());
        }

        @Test
        @TestTransaction
        @DisplayName("findById devuelve vacío si no existe")
        void emptyForUnknownId() {
            assertTrue(repository.findById(ListingId.newId()).isEmpty());
        }


        @Test
        @TestTransaction
        @DisplayName("save sobre un id existente actualiza, no duplica")
        void saveUpdatesInPlace() {
            var draft = ProductListing.draft(SellerId.newId(), "Teclado", PRICE, 40);
            repository.save(draft);
            entityManager.flush();

            repository.save(draft.withStatus(ListingStatus.PUBLISHED).withStock(35));
            entityManager.flush();
            entityManager.clear();

            assertEquals(1, repository.count());
            var actualizado = assertInstanceOf(
                    ProductListing.class, repository.findById(draft.id()).orElseThrow());
            assertEquals(ListingStatus.PUBLISHED, actualizado.status());
            assertEquals(35, actualizado.availableStock());
        }

        @Test
        @TestTransaction
        @DisplayName("deleteById informa de si existía")
        void deleteReportsExistence() {
            var listing = ProductListing.draft(SellerId.newId(), "Teclado", PRICE, 1);
            repository.save(listing);
            entityManager.flush();

            assertTrue(repository.deleteById(listing.id()));
            entityManager.flush();
            assertTrue(repository.findById(listing.id()).isEmpty());
        }


        @Test
        @TestTransaction
        @DisplayName("la base de datos rechaza un producto con stock negativo escrito en SQL crudo")
        void rejectsNegativeStockEvenBypassingTheDomain() {
            // Saltándonos por completo el dominio, las entidades y la validación: SQL directo,
            // como haría un script de migración o un backfill mal hecho.
            var insert = entityManager.createNativeQuery("""
                    insert into listing (id, listing_type, seller_id, title,
                                         price_amount, price_currency, status, available_stock)
                    values (:id, 'PRODUCT', :seller, 'Pirata', 25.00, 'EUR', 'DRAFT', -99)
                    """)
                    .setParameter("id", java.util.UUID.randomUUID())
                    .setParameter("seller", java.util.UUID.randomUUID());

            var error = org.junit.jupiter.api.Assertions.assertThrows(
                    RuntimeException.class, insert::executeUpdate);

            assertTrue(error.toString().contains("listing_product_fields")
                            || error.getCause() != null,
                    "se esperaba una violación de CHECK: " + error);
        }

        @Test
        @TestTransaction
        @DisplayName("la base de datos rechaza un precio de cero escrito en SQL crudo")
        void rejectsZeroPriceEvenBypassingTheDomain() {
            var insert = entityManager.createNativeQuery("""
                    insert into listing (id, listing_type, seller_id, title,
                                         price_amount, price_currency, status, available_stock)
                    values (:id, 'PRODUCT', :seller, 'Gratis', 0.00, 'EUR', 'DRAFT', 1)
                    """)
                    .setParameter("id", java.util.UUID.randomUUID())
                    .setParameter("seller", java.util.UUID.randomUUID());

            org.junit.jupiter.api.Assertions.assertThrows(
                    RuntimeException.class, insert::executeUpdate);
        }

        @Test
        @TestTransaction
        @DisplayName("NUMERIC guarda decimales exactos, no aproximaciones binarias")
        void numericIsExact() {
            var listing = ProductListing.draft(
                    SellerId.newId(), "Teclado", Money.of("0.10", "EUR"), 1);
            repository.save(listing);
            entityManager.flush();

            var valor = (BigDecimal) entityManager
                    .createNativeQuery("select price_amount from listing where id = :id")
                    .setParameter("id", listing.id().value())
                    .getSingleResult();

            // Con DOUBLE PRECISION esto sería 0.1000000000000000055511151231257827...
            assertEquals(0, valor.compareTo(new BigDecimal("0.10")));
        }
}
