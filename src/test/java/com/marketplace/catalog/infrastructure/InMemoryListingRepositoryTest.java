package com.marketplace.catalog.infrastructure;

import com.marketplace.catalog.domain.ListingId;
import com.marketplace.catalog.domain.ListingStatus;
import com.marketplace.catalog.domain.ProductListing;
import com.marketplace.shared.domain.Money;
import com.marketplace.shared.domain.PageRequest;
import com.marketplace.shared.domain.SellerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test del adaptador en memoria <strong>sin arrancar Quarkus</strong>.
 *
 * <p>La clase lleva {@code @ApplicationScoped}, pero eso no impide instanciarla con {@code new}:
 * una anotación no cambia el constructor. Como toda la lógica está en la clase y no en el
 * contenedor, se puede probar en microsegundos.
 *
 * <p>Regla práctica: usa {@code @QuarkusTest} solo cuando lo que pruebas <em>es</em> el
 * framework. Probar tu propia lógica no lo necesita.
 */
@DisplayName("InMemoryListingRepository (sin contenedor CDI)")
class InMemoryListingRepositoryTest {

    private static final Money PRICE = Money.of("25.00", "EUR");

    private InMemoryListingRepository repository;
    private SellerId alice;
    private SellerId bob;

    @BeforeEach
    void setUp() {
        repository = new InMemoryListingRepository();
        alice = SellerId.newId();
        bob = SellerId.newId();
    }

    @Test
    @DisplayName("guarda y recupera por id")
    void savesAndFindsById() {
        var listing = ProductListing.draft(alice, "Teclado mecánico", PRICE, 10);

        repository.save(listing);

        assertEquals(listing, repository.findById(listing.id()).orElseThrow());
        assertEquals(1, repository.count());
    }

    @Test
    @DisplayName("devuelve vacío si el id no existe")
    void returnsEmptyForUnknownId() {
        assertTrue(repository.findById(ListingId.newId()).isEmpty());
    }

    @Test
    @DisplayName("save reemplaza la publicación con el mismo id")
    void saveReplacesSameId() {
        var draft = ProductListing.draft(alice, "Teclado mecánico", PRICE, 10);
        repository.save(draft);

        repository.save(draft.withStatus(ListingStatus.PUBLISHED));

        assertEquals(1, repository.count());
        assertEquals(
                ListingStatus.PUBLISHED,
                repository.findById(draft.id()).orElseThrow().status());
    }

    @Test
    @DisplayName("findBySeller solo devuelve las del vendedor indicado")
    void findsBySeller() {
        repository.save(ProductListing.draft(alice, "Teclado", PRICE, 1));
        repository.save(ProductListing.draft(alice, "Ratón", PRICE, 1));
        repository.save(ProductListing.draft(bob, "Monitor", PRICE, 1));

        var deAlice = repository.findBySeller(alice, PageRequest.first()).items();

        assertEquals(2, deAlice.size());
        assertEquals(List.of("Ratón", "Teclado"), deAlice.stream().map(l -> l.title()).toList());
    }

    @Test
    @DisplayName("findVisible excluye borradores y archivadas")
    void findsOnlyVisible() {
        var borrador = ProductListing.draft(alice, "Borrador", PRICE, 1);
        var publicada = ProductListing.draft(alice, "Publicada", PRICE, 1)
                .withStatus(ListingStatus.PUBLISHED);
        var pausada = ProductListing.draft(alice, "Pausada", PRICE, 1)
                .withStatus(ListingStatus.PAUSED);
        var archivada = ProductListing.draft(alice, "Archivada", PRICE, 1)
                .withStatus(ListingStatus.ARCHIVED);

        repository.save(borrador);
        repository.save(publicada);
        repository.save(pausada);
        repository.save(archivada);

        // PAUSED sigue siendo visible: el comprador la ve, pero no puede comprarla.
        assertEquals(
                List.of("Pausada", "Publicada"),
                repository.findVisible(PageRequest.first()).items().stream().map(l -> l.title()).toList());
    }

    @Test
    @DisplayName("deleteById informa de si existía")
    void deleteReportsWhetherItExisted() {
        var listing = ProductListing.draft(alice, "Teclado", PRICE, 1);
        repository.save(listing);

        assertTrue(repository.deleteById(listing.id()));
        assertFalse(repository.deleteById(listing.id()));
        assertEquals(0, repository.count());
    }
}
