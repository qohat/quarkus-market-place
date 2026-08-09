package com.marketplace.inventory.domain;

import com.marketplace.catalog.domain.ListingId;

/**
 * Las existencias de una publicación: la fuente de verdad sobre cuántas unidades hay.
 *
 * <h2>Por qué esto no es {@code ProductListing}</h2>
 *
 * En el catálogo, una publicación es algo que se muestra: título, precio, fotos. Aquí es un
 * contador. Son dos modelos distintos del mismo objeto del mundo real, y ese es justamente el
 * sentido de un bounded context: la misma palabra significa cosas distintas según quién pregunte,
 * y forzar un único modelo que sirva a todos produce una clase con treinta campos donde cada uno
 * solo importa a una parte.
 *
 * <p>El catálogo conserva su propio {@code availableStock}, pero degradado a dato de escaparate:
 * puede ir atrasado. La regla es <strong>leer la copia para mostrar, nunca para decidir</strong>.
 * Enseñar «quedan 3» con datos de hace dos segundos es aceptable; vender con ellos, no.
 *
 * <h2>Los dos números</h2>
 *
 * <pre>
 *   onHand    unidades que existen físicamente
 *   reserved  unidades apartadas para compras en curso, aún no cobradas
 *   available = onHand - reserved   ← lo único que se puede vender
 * </pre>
 *
 * Hacen falta dos porque comprar no es instantáneo: el comprador tarda en pagar. Descontar al
 * terminar deja que se quede sin stock <em>después</em> de pagar; descontar al empezar deja que
 * un carrito abandonado bloquee inventario para siempre. Con {@code reserved} y una caducidad,
 * ninguna de las dos cosas ocurre.
 *
 * <p>Es un record inmutable: las operaciones devuelven un estado nuevo en vez de mutar. Quien
 * decide si ese estado nuevo llega a la base de datos —y cómo se protege de la concurrencia— es
 * el adaptador, no el dominio.
 */
public record StockItem(ListingId listingId, int onHand, int reserved) {

    public StockItem {
        if (onHand < 0) {
            throw new IllegalArgumentException("onHand cannot be negative: " + onHand);
        }
        if (reserved < 0) {
            throw new IllegalArgumentException("reserved cannot be negative: " + reserved);
        }
        // El invariante que de verdad importa. Si se rompe, ya se ha vendido algo que no existe:
        // por eso se comprueba aquí, en el único punto por el que pasan todas las construcciones,
        // y no en cada operación por separado.
        if (reserved > onHand) {
            throw new IllegalStateException(
                    "reserved (" + reserved + ") cannot exceed onHand (" + onHand + ")");
        }
    }

    public static StockItem of(ListingId listingId, int units) {
        return new StockItem(listingId, units, 0);
    }

    /** Lo que se puede vender ahora mismo. */
    public int available() {
        return onHand - reserved;
    }

    /**
     * Aparta unidades para una compra en curso.
     *
     * @throws InsufficientStockException si no hay suficientes disponibles
     */
    public StockItem reserve(int units) {
        requirePositive(units);
        if (units > available()) {
            throw new InsufficientStockException(listingId, units, available());
        }
        return new StockItem(listingId, onHand, reserved + units);
    }

    /**
     * Devuelve unidades reservadas al inventario: el comprador canceló, o la reserva caducó.
     * Las unidades vuelven a estar disponibles y {@code onHand} no cambia, porque nunca llegaron
     * a salir del almacén.
     */
    public StockItem release(int units) {
        requirePositive(units);
        if (units > reserved) {
            throw new IllegalStateException(
                    "cannot release " + units + " units, only " + reserved + " are reserved");
        }
        return new StockItem(listingId, onHand, reserved - units);
    }

    /**
     * El pago se completó: las unidades salen definitivamente.
     *
     * <p>Bajan los dos contadores a la vez, y ahí está el detalle: {@code available()} no cambia
     * al confirmar. Ya se había descontado al reservar. Confirmar no vende nada nuevo, solo hace
     * definitivo lo que ya estaba apartado.
     */
    public StockItem confirm(int units) {
        requirePositive(units);
        if (units > reserved) {
            throw new IllegalStateException(
                    "cannot confirm " + units + " units, only " + reserved + " are reserved");
        }
        return new StockItem(listingId, onHand - units, reserved - units);
    }

    /** Reposición: llegó mercancía nueva. */
    public StockItem restock(int units) {
        requirePositive(units);
        return new StockItem(listingId, onHand + units, reserved);
    }

    private static void requirePositive(int units) {
        if (units <= 0) {
            throw new IllegalArgumentException("units must be greater than zero: " + units);
        }
    }
}
