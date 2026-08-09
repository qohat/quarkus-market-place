package com.marketplace.catalog.application;

import com.marketplace.inventory.application.Inventory;
import com.marketplace.inventory.domain.BuyerId;
import com.marketplace.shared.domain.Money;
import com.marketplace.shared.domain.PageRequest;
import com.marketplace.shared.domain.SellerId;
import com.marketplace.shared.outbox.OutboxRelay;
import com.marketplace.support.DatabaseCleaner;
import com.marketplace.support.TransactionalRunner;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La caché del catálogo y —lo que de verdad importa— su invalidación.
 *
 * <p>Que una caché acelere es fácil de conseguir y aburrido de probar. Lo difícil es que
 * <strong>no sirva datos viejos más de lo previsto</strong>, y eso es lo que se comprueba aquí,
 * incluida la ventana en la que sí los sirve.
 */
@QuarkusTest
@DisplayName("Caché del catálogo")
class CatalogCacheTest {

    private static final BuyerId COMPRADOR = new BuyerId(UUID.randomUUID());

    @Inject
    ListingCatalog catalog;

    @Inject
    Inventory inventory;

    @Inject
    OutboxRelay relay;

    @Inject
    EntityManager entityManager;

    @Inject
    DatabaseCleaner database;

    @Inject
    TransactionalRunner tx;

    @BeforeEach
    void setUp() {
        database.clear();
        tx.run(() -> catalog.invalidateBrowseCache());
    }

    @AfterEach
    void tearDown() {
        database.clear();
        tx.run(() -> catalog.invalidateBrowseCache());
    }

    private Statistics estadisticas() {
        return entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getStatistics();
    }

    /** Ejecuta la acción y devuelve cuántas sentencias JDBC hicieron falta. */
    private long consultasDe(Runnable accion) {
        var stats = estadisticas();
        stats.clear();
        accion.run();
        return stats.getPrepareStatementCount();
    }

    private int publicacionesVisibles() {
        return tx.call(() -> catalog.browse(PageRequest.first())).items().size();
    }

    /**
     * Se cuenta el número de CONSULTAS, no el resultado.
     *
     * <p>La primera versión de este test comprobaba que una publicación nueva «no aparecía
     * todavía» por estar cacheada. Dejó de valer en cuanto las transiciones de estado pasaron a
     * invalidar la caché — y esa invalidación es correcta, así que el test estaba mal, no el
     * código.
     *
     * <p>Contar consultas es una forma mucho más honesta de probar una caché: mide el efecto que
     * se busca —ahorrar trabajo a la base de datos— en vez de un síntoma indirecto que además era
     * un dato obsoleto. Es la técnica del presupuesto de consultas del módulo 3, aplicada aquí.
     */
    @Test
    @DisplayName("la segunda consulta no llega a la base de datos")
    void theSecondQueryDoesNotHitTheDatabase() {
        var seller = SellerId.newId();
        var listing = tx.call(() ->
                catalog.createProduct(seller, "Teclado", Money.of("25.00", "EUR"), 10));
        tx.run(() -> catalog.publish(listing.id(), seller));

        long primera = consultasDe(() -> tx.call(() -> catalog.browse(PageRequest.first())));
        long segunda = consultasDe(() -> tx.call(() -> catalog.browse(PageRequest.first())));

        assertTrue(primera > 0, "la primera consulta sí debe ir a la base de datos");
        assertEquals(0, segunda, "la segunda debe salir de la caché sin tocar la base de datos");
    }

    @Test
    @DisplayName("invalidar hace que el siguiente resultado sea el real")
    void invalidatingRevealsTheRealResult() {
        var seller = SellerId.newId();
        var listing = tx.call(() ->
                catalog.createProduct(seller, "Teclado", Money.of("25.00", "EUR"), 10));
        tx.run(() -> catalog.publish(listing.id(), seller));
        tx.run(() -> catalog.invalidateBrowseCache());
        assertEquals(1, publicacionesVisibles());

        var otra = tx.call(() ->
                catalog.createProduct(seller, "Ratón", Money.of("15.00", "EUR"), 5));
        tx.run(() -> catalog.publish(otra.id(), seller));
        tx.run(() -> catalog.invalidateBrowseCache());

        assertEquals(2, publicacionesVisibles());
    }

    @Test
    @DisplayName("cada página tiene su propia entrada")
    void eachPageIsCachedSeparately() {
        var seller = SellerId.newId();
        for (int i = 0; i < 5; i++) {
            var l = tx.call(() ->
                    catalog.createProduct(seller, "Producto " + UUID.randomUUID(),
                            Money.of("10.00", "EUR"), 1));
            tx.run(() -> catalog.publish(l.id(), seller));
        }
        tx.run(() -> catalog.invalidateBrowseCache());

        // Si la clave de caché no incluyera el PageRequest, la segunda llamada devolvería la
        // respuesta de la primera y todas las páginas mostrarían lo mismo. Que PageRequest sea
        // un record —con equals y hashCode correctos— es lo que hace esto seguro.
        var primera = tx.call(() -> catalog.browse(PageRequest.of(0, 2)));
        var segunda = tx.call(() -> catalog.browse(PageRequest.of(1, 2)));

        assertEquals(2, primera.items().size());
        assertEquals(2, segunda.items().size());
        assertEquals(0, primera.items().stream()
                .filter(item -> segunda.items().contains(item)).count(),
                "las páginas no deben solaparse");
    }

    @Test
    @DisplayName("EL CIERRE DEL CURSO: un evento de stock invalida la caché solo")
    void aStockEventInvalidatesTheCacheOnItsOwn() {
        var seller = SellerId.newId();
        var listing = tx.call(() ->
                catalog.createProduct(seller, "Teclado", Money.of("25.00", "EUR"), 10));
        tx.run(() -> catalog.publish(listing.id(), seller));
        tx.run(() -> inventory.track(listing.id(), 10));
        tx.run(() -> catalog.invalidateBrowseCache());

        // Se cachea el estado actual.
        assertEquals(10, ((com.marketplace.catalog.domain.ProductListing)
                tx.call(() -> catalog.browse(PageRequest.first())).items().getFirst())
                .availableStock());

        // Y ahora cambia el stock por el camino largo: Inventario reserva → outbox → relay →
        // Kafka → el consumidor actualiza la proyección Y VACÍA LA CACHÉ.
        //
        // Nadie llamó a invalidateBrowseCache() a mano. La invalidación viene de que el módulo 7
        // dejó un evento que dice exactamente cuándo los datos cambiaron: en vez de expirar por
        // tiempo y servir datos viejos «por si acaso», se invalida cuando hay motivo.
        tx.run(() -> inventory.reserve(listing.id(), COMPRADOR, 4));
        tx.run(() -> relay.publishPending());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertEquals(6, ((com.marketplace.catalog.domain.ProductListing)
                        tx.call(() -> catalog.browse(PageRequest.first())).items().getFirst())
                        .availableStock()));
    }
}
