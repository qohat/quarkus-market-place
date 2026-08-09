package com.marketplace.inventory.infrastructure.persistence;

import com.marketplace.catalog.domain.ListingId;
import com.marketplace.inventory.domain.InsufficientStockException;
import com.marketplace.inventory.domain.StockItem;
import com.marketplace.inventory.domain.StockItemNotFoundException;
import com.marketplace.inventory.domain.StockRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.persistence.EntityManager;

import java.util.Optional;

/**
 * ESTRATEGIA 3 — actualización atómica condicional.
 *
 * <p>No lee antes de escribir. Manda una única sentencia cuya cláusula {@code WHERE} <em>es</em>
 * la comprobación:
 *
 * <pre>
 *   UPDATE stock_item
 *      SET reserved = reserved + :units
 *    WHERE listing_id = :id AND (on_hand - reserved) &gt;= :units
 * </pre>
 *
 * <p>PostgreSQL evalúa la condición y aplica el cambio dentro de la misma operación atómica, sin
 * ventana entre leer y escribir. <strong>Si afecta a 0 filas, no había stock.</strong> No hay
 * conflicto que detectar ni fila que bloquear: dos transacciones simultáneas se serializan solas
 * sobre esa fila, durante los microsegundos que dura el UPDATE, y ambas terminan.
 *
 * <h2>Por qué gana bajo contención</h2>
 *
 * <pre>
 *   optimista   199 rechazados tras hacer el trabajo entero y tirarlo
 *   pesimista   199 esperando en cola, cada uno reteniendo una conexión
 *   atómico     199 UPDATE que afectan a 0 filas y terminan
 * </pre>
 *
 * Ninguno sobrevende. La diferencia está en el coste de decir que no.
 *
 * <h2>El precio, que es real</h2>
 *
 * La regla de negocio ya no está en {@link StockItem#reserve(int)}: está en un {@code WHERE}.
 * La misma condición vive <strong>en dos sitios</strong>, Java y SQL, y nada obliga a que sigan
 * de acuerdo dentro de un año. Una regla nueva —«máximo 10 por comprador»— habría que escribirla
 * dos veces, y si solo se toca el dominio, este adaptador la ignora en silencio.
 *
 * <p>Por eso {@code StockConcurrencyTest} ejecuta la misma batería contra las tres
 * implementaciones: es lo único que mantiene honesta esta duplicación.
 *
 * <p>Y por eso no es la respuesta a todo. Funciona porque reservar es una operación
 * aritmética que cabe en una sentencia. Una regla que dependa de leer varias filas, o de
 * consultar otro servicio, no se puede expresar así.
 */
@ApplicationScoped
@Typed(AtomicStockRepository.class)
public class AtomicStockRepository implements StockRepository {

    /**
     * La condición de disponibilidad, en SQL. Al no leer previamente, la comprobación y el
     * cambio ocurren en el mismo instante.
     */
    private static final String RESERVE = """
            update StockItemEntity s
               set s.reserved = s.reserved + :units, s.updatedAt = current_timestamp
             where s.listingId = :id and (s.onHand - s.reserved) >= :units
            """;

    /** Devolver unidades: la condición protege de liberar más de lo reservado. */
    private static final String RELEASE = """
            update StockItemEntity s
               set s.reserved = s.reserved - :units, s.updatedAt = current_timestamp
             where s.listingId = :id and s.reserved >= :units
            """;

    /** Confirmar baja los dos contadores a la vez: las unidades salen del almacén. */
    private static final String CONFIRM = """
            update StockItemEntity s
               set s.onHand = s.onHand - :units,
                   s.reserved = s.reserved - :units,
                   s.updatedAt = current_timestamp
             where s.listingId = :id and s.reserved >= :units
            """;

    private final EntityManager entityManager;

    AtomicStockRepository(EntityManager entityManager) {
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
        if (units <= 0) {
            throw new IllegalArgumentException("units must be greater than zero: " + units);
        }
        if (execute(RESERVE, listingId, units) == 0) {
            // Cero filas significa una de dos cosas, y hay que distinguirlas: o no existen
            // existencias para esa publicación, o existen y no alcanzan. Esta lectura solo
            // ocurre en el camino de fallo, así que no penaliza al que sí compra.
            var actual = find(listingId).orElseThrow(
                    () -> new StockItemNotFoundException(listingId));
            throw new InsufficientStockException(listingId, units, actual.available());
        }
        return reload(listingId);
    }

    @Override
    public StockItem release(ListingId listingId, int units) {
        return applyOrFail(RELEASE, listingId, units, "release");
    }

    @Override
    public StockItem confirm(ListingId listingId, int units) {
        return applyOrFail(CONFIRM, listingId, units, "confirm");
    }

    private StockItem applyOrFail(String sql, ListingId listingId, int units, String operation) {
        if (units <= 0) {
            throw new IllegalArgumentException("units must be greater than zero: " + units);
        }
        if (execute(sql, listingId, units) == 0) {
            var actual = find(listingId).orElseThrow(
                    () -> new StockItemNotFoundException(listingId));
            throw new IllegalStateException("cannot " + operation + " " + units
                    + " units, only " + actual.reserved() + " are reserved");
        }
        return reload(listingId);
    }

    private int execute(String sql, ListingId listingId, int units) {
        return entityManager.createQuery(sql)
                .setParameter("id", listingId.value())
                .setParameter("units", units)
                .executeUpdate();
    }

    /**
     * Relee el estado tras la escritura.
     *
     * <p>El {@code clear()} no es opcional: una consulta de actualización masiva pasa por encima
     * de la sesión de Hibernate, así que la caché de primer nivel podría devolver la versión
     * <em>anterior</em> del objeto —la que se leyó antes del UPDATE— y el llamante vería un
     * estado que ya no existe. Es el mismo mecanismo de caché que en el módulo 3 hizo desaparecer
     * un problema N+1, mordiendo esta vez en la dirección contraria.
     */
    private StockItem reload(ListingId listingId) {
        entityManager.flush();
        entityManager.clear();
        return find(listingId).orElseThrow(() -> new StockItemNotFoundException(listingId));
    }
}
