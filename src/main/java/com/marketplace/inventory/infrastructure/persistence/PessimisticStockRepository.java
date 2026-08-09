package com.marketplace.inventory.infrastructure.persistence;

import com.marketplace.catalog.domain.ListingId;
import com.marketplace.inventory.domain.StockItem;
import com.marketplace.inventory.domain.StockItemNotFoundException;
import com.marketplace.inventory.domain.StockRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * ESTRATEGIA 2 — bloqueo pesimista.
 *
 * <p>Pide la fila con {@code SELECT ... FOR UPDATE}: PostgreSQL se la reserva a esta transacción
 * y cualquier otra que quiera la misma fila <strong>espera</strong> hasta el commit. No hay
 * conflicto que detectar porque no puede producirse.
 *
 * <h2>Lo bueno</h2>
 *
 * Nadie tira trabajo a la basura y nadie reintenta. Bajo contención alta sobre una misma fila es
 * más predecible que el optimismo: las transacciones se ponen en fila y salen de una en una.
 *
 * <h2>Lo malo</h2>
 *
 * Mientras espera, la transacción <strong>retiene su conexión del pool</strong>. Y ahí se juntan
 * los dos módulos anteriores: con 20 conexiones y 200 compradores esperando por la misma fila, el
 * pool se agota y la aplicación deja de responder <em>entera</em>, incluidas peticiones que no
 * tenían nada que ver con ese producto. Una fila caliente se convierte en una caída general.
 *
 * <p>Además hay riesgo de <em>deadlock</em> en cuanto una operación bloquee dos filas: si una
 * transacción toma A y luego B mientras otra toma B y luego A, PostgreSQL detecta el ciclo y mata
 * a una. Se evita bloqueando siempre en el mismo orden.
 *
 * <h2>El detalle de configuración que decide si esto es usable</h2>
 *
 * Sin tiempo de espera, un bloqueo se aguanta indefinidamente. Con
 * {@code jakarta.persistence.lock.timeout} la espera falla rápido, que casi siempre es mejor
 * servicio que esperar eternamente. Ese ajuste convierte «la aplicación se cuelga» en «esta
 * petición falla», y es la diferencia entre un incidente y una incidencia.
 */
@ApplicationScoped
@Typed(PessimisticStockRepository.class)
public class PessimisticStockRepository implements StockRepository {

    private final EntityManager entityManager;

    PessimisticStockRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void create(StockItem item) {
        entityManager.persist(StockItemEntity.fromDomain(item));
    }

    /** La lectura simple NO bloquea: consultar existencias no debe frenar a quien compra. */
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
     * Igual que la versión optimista salvo por el {@link LockModeType#PESSIMISTIC_WRITE}, que es
     * lo que añade el {@code FOR UPDATE} a la consulta.
     *
     * <p>Fíjate en que la regla de negocio sigue siendo la misma llamada al dominio: cambiar de
     * estrategia de concurrencia no toca ni una línea de lógica de negocio. Eso es exactamente lo
     * que compra la separación entre puerto y adaptador.
     */
    private StockItem apply(ListingId listingId, UnaryOperator<StockItem> operation) {
        var entity = entityManager.find(
                StockItemEntity.class, listingId.value(), LockModeType.PESSIMISTIC_WRITE);
        if (entity == null) {
            throw new StockItemNotFoundException(listingId);
        }
        var updated = operation.apply(entity.toDomain());
        entity.updateFrom(updated);
        return updated;
    }
}
