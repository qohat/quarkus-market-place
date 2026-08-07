package com.marketplace.catalog.infrastructure;

import com.marketplace.catalog.domain.ListingRepository;
import com.marketplace.catalog.domain.ProductListing;
import com.marketplace.shared.domain.Money;
import com.marketplace.shared.domain.SellerId;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Este sí arranca Quarkus, porque lo que se está probando <strong>es ARC</strong>: que resuelve
 * la interfaz a su única implementación y que aplica el scope correcto.
 *
 * <p>Es el primer {@code @QuarkusTest} del proyecto. Nota que arranca la aplicación entera —
 * mucho más lento que los tests de dominio. Por eso se reservan para lo que de verdad los pide.
 */
@QuarkusTest
@DisplayName("ARC resolviendo el puerto ListingRepository")
class ListingRepositoryCdiTest {

    /**
     * Inyectamos la <strong>interfaz</strong>, no la clase concreta. ARC encuentra en build time
     * que solo hay un bean que la implementa y resuelve la ambigüedad ahí mismo. Si hubiera dos
     * implementaciones sin desempate, el build fallaría con "Ambiguous dependencies" — un error
     * de compilación, no una sorpresa en producción.
     */
    @Inject
    ListingRepository repository;

    /** El mismo bean, pedido por su tipo concreto. */
    @Inject
    InMemoryListingRepository concrete;

    @Test
    @DisplayName("lo que se inyecta es un client proxy, no la instancia real")
    void injectsAClientProxy() {
        String injectedClass = repository.getClass().getName();

        // ARC generó en build time una subclase InMemoryListingRepository_ClientProxy.
        // Cada llamada pasa por ella y delega en la instancia real del contexto activo.
        assertTrue(
                injectedClass.contains("ClientProxy"),
                "Se esperaba un client proxy, pero llegó: " + injectedClass);
        assertNotEquals(InMemoryListingRepository.class.getName(), injectedClass);
    }

    @Test
    @DisplayName("@ApplicationScoped: hay una única instancia compartida")
    void applicationScopedSharesOneInstance() {
        concrete.clear();
        var listing = ProductListing.draft(
                SellerId.newId(), "Teclado mecánico", Money.of("25.00", "EUR"), 10);

        // Escribimos por una referencia inyectada...
        repository.save(listing);

        // ...y leemos por la otra. Dos proxies distintos, la misma instancia detrás.
        assertEquals(1, concrete.count());
        assertEquals(listing, concrete.findById(listing.id()).orElseThrow());

        concrete.clear();
    }
}
