package com.marketplace.inventory.infrastructure.persistence;

import com.marketplace.catalog.domain.ListingId;
import com.marketplace.inventory.domain.StockItem;
import com.marketplace.inventory.domain.StockItemNotFoundException;
import com.marketplace.inventory.domain.StockRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.persistence.EntityManager;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * ESTRATEGIA 1 — bloqueo optimista.
 *
 * <p>Lee, decide en el dominio y escribe confiando en que nadie se haya adelantado. Hibernate
 * añade {@code AND version = ?} al {@code UPDATE}; si otra transacción escribió antes, afecta a 0
 * filas y salta {@code OptimisticLockException}.
 *
 * <h2>Lo bueno</h2>
 *
 * La regla de negocio vive donde debe: {@link StockItem#reserve(int)}. Este adaptador no sabe
 * qué significa reservar, solo coordina. Añadir mañana «no se puede reservar más de 10 unidades
 * por comprador» se hace en el dominio y esta clase no se entera.
 *
 * <h2>Lo malo, y es serio bajo contención</h2>
 *
 * Cada intento fallido ha hecho ya todo el trabajo —consulta, construcción del objeto, decisión—
 * antes de descubrir que no sirve. Con 200 compradores sobre una unidad, 199 tiran ese trabajo.
 * Y si se reintenta, vuelven a chocar entre ellos: es el patrón que degrada un flash sale hasta
 * que el sistema deja de avanzar.
 *
 * <p><strong>El optimismo es una apuesta sobre la probabilidad de conflicto.</strong> Con poca
 * contención es la mejor opción de las tres, porque no bloquea nada. Con mucha, es la peor.
 *
 * <p>{@code @Typed} deja este bean inyectable <em>solo</em> por su clase concreta y no por
 * {@link StockRepository}: si las tres implementaciones se ofrecieran por la interfaz, ARC no
 * sabría cuál elegir y fallaría el build con una dependencia ambigua. Quién es el
 * {@code StockRepository} de la aplicación lo decide {@code StockRepositoryProducer}.
 */
@ApplicationScoped
@Typed(OptimisticStockRepository.class)
public class OptimisticStockRepository implements StockRepository {

    private final EntityManager entityManager;

    OptimisticStockRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void create(StockItem item) {
        entityManager.persist(StockItemEntity.fromDomain(item));
    }

    @Override
    public Optional<StockItem> find(ListingId listingId) {
        return Optional.ofNullable(entityManager.find(StockItemEntity.class, listingId.value()))
                .map(StockItemEntity::toDomain);
    }

    @Override
    public StockItem reserve(ListingId listingId, int units) {
        return apply(listingId, stock -> stock.reserve(units));
    }

    @Override
    public StockItem release(ListingId listingId, int units) {
        return apply(listingId, stock -> stock.release(units));
    }

    @Override
    public StockItem confirm(ListingId listingId, int units) {
        return apply(listingId, stock -> stock.confirm(units));
    }

    /**
     * Lee, delega la decisión en el dominio y vuelca el resultado.
     *
     * <p>No hay {@code save} explícito: la entidad está gestionada, así que al confirmar la
     * transacción el <em>dirty checking</em> de Hibernate detecta el cambio y emite el
     * {@code UPDATE} con la comprobación de versión. Es también la razón de que la
     * {@code OptimisticLockException} no salte aquí, sino al hacer commit —lejos de esta línea—,
     * que es lo que la hace incómoda de manejar.
     */
    private StockItem apply(ListingId listingId, UnaryOperator<StockItem> operation) {
        var entity = entityManager.find(StockItemEntity.class, listingId.value());
        if (entity == null) {
            throw new StockItemNotFoundException(listingId);
        }
        var updated = operation.apply(entity.toDomain());
        entity.updateFrom(updated);
        return updated;
    }
}
