package com.marketplace.catalog.infrastructure.persistence;

import com.marketplace.catalog.domain.Listing;
import com.marketplace.catalog.domain.ListingRepository;
import com.marketplace.catalog.domain.ListingStatus;
import com.marketplace.catalog.domain.ProductListing;
import com.marketplace.shared.domain.Money;
import com.marketplace.shared.domain.PageRequest;
import com.marketplace.shared.domain.SellerId;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paginación contra PostgreSQL.
 *
 * <p>El test más importante de esta clase es {@code everyItemAppearsExactlyOnce}: recorre todas
 * las páginas de un conjunto con títulos duplicados y comprueba que ningún elemento se repite ni
 * se pierde. Es la comprobación que detecta el bug del orden inestable, que ningún test de una
 * sola página encontraría jamás.
 */
@QuarkusTest
@DisplayName("Paginación")
class PaginationTest {

    @Inject
    ListingRepository repository;

    @Inject
    EntityManager entityManager;

    private static final Money PRICE = Money.of("25.00", "EUR");

    private SellerId crear(int cuantos, String titulo) {
        var seller = SellerId.newId();
        for (int i = 0; i < cuantos; i++) {
            repository.save(ProductListing
                    .draft(seller, titulo, PRICE, 1)
                    .withStatus(ListingStatus.PUBLISHED));
        }
        entityManager.flush();
        return seller;
    }

    @Test
    @TestTransaction
    @DisplayName("devuelve solo el tamaño pedido y el total correcto")
    void returnsRequestedSize() {
        crear(25, "Teclado");

        var pagina = repository.findVisible(PageRequest.of(0, 10));

        assertEquals(10, pagina.items().size());
        assertEquals(25, pagina.totalItems());
        assertEquals(3, pagina.totalPages());   // 10 + 10 + 5
        assertTrue(pagina.hasNext());
        assertFalse(pagina.hasPrevious());
    }

    @Test
    @TestTransaction
    @DisplayName("la última página trae el resto y no anuncia siguiente")
    void lastPageIsPartial() {
        crear(25, "Teclado");

        var ultima = repository.findVisible(PageRequest.of(2, 10));

        assertEquals(5, ultima.items().size());
        assertFalse(ultima.hasNext());
        assertTrue(ultima.hasPrevious());
    }

    @Test
    @TestTransaction
    @DisplayName("una página más allá del final viene vacía, no falla")
    void pastTheEndIsEmpty() {
        crear(5, "Teclado");

        var lejana = repository.findVisible(PageRequest.of(99, 10));

        assertTrue(lejana.items().isEmpty());
        assertEquals(5, lejana.totalItems());
        assertFalse(lejana.hasNext());
    }

    @Test
    @TestTransaction
    @DisplayName("sin resultados devuelve una página vacía coherente")
    void emptyResultIsConsistent() {
        var vacia = repository.findVisible(PageRequest.first());

        assertTrue(vacia.items().isEmpty());
        assertEquals(0, vacia.totalItems());
        assertEquals(0, vacia.totalPages());
        assertFalse(vacia.hasNext());
    }

    @Test
    @TestTransaction
    @DisplayName("cada elemento aparece exactamente una vez al recorrer todas las páginas")
    void everyItemAppearsExactlyOnce() {
        // TODOS con el mismo título: sin desempate por id, el orden que devuelve PostgreSQL
        // para estas 30 filas no está garantizado y puede variar entre consultas. Al pedir
        // páginas sucesivas, algunas filas saldrían dos veces y otras ninguna.
        crear(30, "Teclado");

        var vistos = new ArrayList<String>();
        int size = 7;
        for (int page = 0; page * size < 30; page++) {
            repository.findVisible(PageRequest.of(page, size)).items()
                    .forEach(listing -> vistos.add(listing.id().toString()));
        }

        assertEquals(30, vistos.size(), "se perdieron o duplicaron elementos entre páginas");
        assertEquals(30, Set.copyOf(vistos).size(), "algún elemento apareció en dos páginas");
    }

    @Test
    @TestTransaction
    @DisplayName("el orden es idéntico entre consultas repetidas")
    void orderIsDeterministic() {
        crear(15, "Teclado");

        List<String> primera = ids(repository.findVisible(PageRequest.of(0, 5)).items());
        List<String> segunda = ids(repository.findVisible(PageRequest.of(0, 5)).items());
        List<String> tercera = ids(repository.findVisible(PageRequest.of(0, 5)).items());

        assertEquals(primera, segunda);
        assertEquals(segunda, tercera);
    }

    @Test
    @TestTransaction
    @DisplayName("findBySeller pagina aislando a cada vendedor")
    void sellerPaginationIsIsolated() {
        var alice = crear(12, "Teclado de Alice");
        crear(30, "Teclado de Bob");

        var pagina = repository.findBySeller(alice, PageRequest.of(0, 5));

        assertEquals(5, pagina.items().size());
        assertEquals(12, pagina.totalItems(), "el total no debe incluir las de Bob");
    }

    @Test
    @DisplayName("PageRequest rechaza tamaños abusivos antes de tocar la base de datos")
    void rejectsAbusiveSizes() {
        // ?size=1000000 sería una denegación de servicio de un solo carácter.
        assertThrows(IllegalArgumentException.class, () -> PageRequest.of(0, 1_000_000));
        assertThrows(IllegalArgumentException.class, () -> PageRequest.of(0, 0));
        assertThrows(IllegalArgumentException.class, () -> PageRequest.of(-1, 10));
    }

    private static List<String> ids(List<Listing> listings) {
        return listings.stream().map(l -> l.id().toString()).toList();
    }
}
