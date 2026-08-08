package com.marketplace.catalog.infrastructure.persistence;

import com.marketplace.catalog.domain.ListingRepository;
import com.marketplace.catalog.domain.ListingStatus;
import com.marketplace.catalog.domain.ProductListing;
import com.marketplace.shared.domain.Money;
import com.marketplace.shared.domain.SellerId;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bloqueo optimista con {@code @Version}.
 *
 * <p>Simular concurrencia real en un test es incómodo: haría falta orquestar dos hilos con dos
 * transacciones y sincronizarlos en el punto justo. Aquí se consigue el mismo efecto de forma
 * determinista escribiendo por debajo con SQL nativo, que es exactamente lo que "otra transacción
 * llegó antes" significa desde el punto de vista de Hibernate.
 */
@QuarkusTest
@DisplayName("Bloqueo optimista")
class OptimisticLockingTest {

    @Inject
    ListingRepository repository;

    @Inject
    EntityManager entityManager;

    private static final Money PRICE = Money.of("25.00", "EUR");

    @Test
    @TestTransaction
    @DisplayName("la versión empieza en 0 y sube con cada actualización")
    void versionIncrementsOnUpdate() {
        var listing = ProductListing.draft(SellerId.newId(), "Teclado", PRICE, 10);
        repository.save(listing);
        entityManager.flush();

        assertEquals(0L, versionEnBd(listing.id().value()));

        repository.save(listing.withStatus(ListingStatus.PUBLISHED));
        entityManager.flush();

        assertEquals(1L, versionEnBd(listing.id().value()));
    }

    @Test
    @TestTransaction
    @DisplayName("una escritura sobre una versión obsoleta se rechaza")
    void staleWriteIsRejected() {
        var listing = ProductListing.draft(SellerId.newId(), "Teclado", PRICE, 10);
        repository.save(listing);
        entityManager.flush();
        entityManager.clear();

        // Cargamos la entidad: nuestra copia en memoria tiene version = 0.
        var gestionada = entityManager.find(ListingEntity.class, listing.id().value());
        assertEquals(0L, gestionada.version);

        // Alguien más escribe primero. En la vida real sería otra petición HTTP; aquí basta con
        // saltarse la sesión de Hibernate, que es como se ve desde dentro.
        entityManager.createNativeQuery(
                        "update listing set title = 'Ganó el otro', version = version + 1 "
                                + "where id = :id")
                .setParameter("id", listing.id().value())
                .executeUpdate();

        // Ahora intentamos escribir nuestra copia obsoleta. Hibernate emitirá
        //   UPDATE listing SET ..., version = 1 WHERE id = ? AND version = 0
        // que afecta a cero filas, y de ahí la excepción.
        gestionada.title = "Ganamos nosotros";

        assertThrows(OptimisticLockException.class, () -> entityManager.flush());
    }

    @Test
    @TestTransaction
    @DisplayName("el perdedor no pisa el cambio del ganador")
    void loserDoesNotOverwriteTheWinner() {
        var listing = ProductListing.draft(SellerId.newId(), "Original", PRICE, 10);
        repository.save(listing);
        entityManager.flush();
        entityManager.clear();

        var gestionada = entityManager.find(ListingEntity.class, listing.id().value());

        entityManager.createNativeQuery(
                        "update listing set title = 'Ganador', version = version + 1 "
                                + "where id = :id")
                .setParameter("id", listing.id().value())
                .executeUpdate();

        gestionada.title = "Perdedor";
        assertThrows(OptimisticLockException.class, () -> entityManager.flush());

        // Sin @Version, este assert fallaría: el título sería 'Perdedor' y el cambio del
        // ganador habría desaparecido sin dejar rastro. Eso es un lost update.
        entityManager.clear();
        var titulo = entityManager.createNativeQuery("select title from listing where id = :id")
                .setParameter("id", listing.id().value())
                .getSingleResult();
        assertEquals("Ganador", titulo);
    }

    @Test
    @TestTransaction
    @DisplayName("una entidad nueva nace con versión 0")
    void newEntityStartsAtZero() {
        var listing = ProductListing.draft(SellerId.newId(), "Teclado", PRICE, 1);

        repository.save(listing);
        entityManager.flush();

        assertTrue(versionEnBd(listing.id().value()) == 0L);
    }

    private long versionEnBd(java.util.UUID id) {
        return ((Number) entityManager
                .createNativeQuery("select version from listing where id = :id")
                .setParameter("id", id)
                .getSingleResult()).longValue();
    }
}
