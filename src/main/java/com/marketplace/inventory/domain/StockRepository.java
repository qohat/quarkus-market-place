package com.marketplace.inventory.domain;

import com.marketplace.catalog.domain.ListingId;

import java.util.Optional;

/**
 * El puerto de salida de Inventario.
 *
 * <h2>Por qué expone {@code reserve} y no solo {@code find} + {@code save}</h2>
 *
 * Un repositorio al uso ofrecería leer y guardar, y el caso de uso haría
 * {@code find → reserve → save}. Ese diseño <strong>impide</strong> la estrategia más eficiente
 * contra la sobreventa, porque obliga a leer antes de escribir y deja una ventana entre ambas:
 *
 * <pre>
 *   A lee stock=1 ──┐
 *   B lee stock=1 ──┤  los dos creen que pueden vender
 *   A escribe 0   ──┤
 *   B escribe 0   ──┘  dos ventas, una unidad
 * </pre>
 *
 * Declarando la <em>intención de negocio</em> —«reserva dos unidades»— en lugar del mecanismo,
 * cada adaptador puede resolverla como quiera: leyendo y reintentando, bloqueando la fila, o con
 * una única sentencia atómica que no lee nada. Es la diferencia entre un puerto que describe
 * <em>qué</em> se quiere y uno que describe <em>cómo</em> se hace.
 *
 * <p>Este módulo tiene tres implementaciones a propósito, para medirlas:
 *
 * <ul>
 *   <li>{@code OptimisticStockRepository} — {@code @Version}, falla y reintenta</li>
 *   <li>{@code PessimisticStockRepository} — {@code SELECT ... FOR UPDATE}, espera turno</li>
 *   <li>{@code AtomicStockRepository} — {@code UPDATE ... WHERE available >= n}</li>
 * </ul>
 *
 * <h2>El precio de la estrategia atómica</h2>
 *
 * Las dos primeras aplican la regla llamando a {@link StockItem#reserve(int)}: la lógica vive en
 * el dominio, en un solo sitio. La tercera la expresa en la cláusula {@code WHERE} de un
 * {@code UPDATE}, así que <strong>la misma regla queda escrita dos veces</strong>, en Java y en
 * SQL, y nada garantiza que sigan diciendo lo mismo dentro de un año.
 *
 * <p>Es una duplicación consciente, no un descuido, y es el trade-off central del módulo: se paga
 * con un test que compruebe que ambas rutas se comportan igual.
 *
 * <h2>Sobre importar {@code ListingId} del catálogo</h2>
 *
 * Compartir el identificador entre contextos es deliberado: es el punto de encuentro entre ellos,
 * y darle a Inventario un id propio obligaría a mantener una tabla de correspondencias sin ganar
 * nada. Lo que <strong>no</strong> se comparte es el modelo: aquí no entra ni {@code Listing} ni
 * {@code Money} ni el estado de publicación. La frontera protege los conceptos, no los números.
 */
public interface StockRepository {

    /** Da de alta las existencias iniciales de una publicación. */
    void create(StockItem item);

    Optional<StockItem> find(ListingId listingId);

    /**
     * Aparta unidades para una compra en curso.
     *
     * @return el estado resultante de las existencias
     * @throws InsufficientStockException si no hay disponibles suficientes
     * @throws StockItemNotFoundException si la publicación no tiene existencias registradas
     */
    StockItem reserve(ListingId listingId, int units);

    /** Devuelve al inventario unidades reservadas: cancelación o caducidad. */
    StockItem release(ListingId listingId, int units);

    /** El pago se completó: las unidades salen definitivamente. */
    StockItem confirm(ListingId listingId, int units);
}
