package com.marketplace.inventory.domain;

import com.marketplace.catalog.domain.ListingId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * El ciclo de vida de unas existencias, sin base de datos ni framework.
 *
 * <p>Aquí se prueba la <em>regla</em>: qué puede pasar con un contador de unidades. Que dos
 * compradores simultáneos no puedan llevarse la misma unidad es un problema distinto —de
 * concurrencia, no de dominio— y se prueba contra PostgreSQL en {@code StockConcurrencyTest}.
 */
@DisplayName("StockItem")
class StockItemTest {

    private static final ListingId TECLADO = ListingId.newId();

    @Test
    @DisplayName("unas existencias nuevas están todas disponibles")
    void newStockIsFullyAvailable() {
        var stock = StockItem.of(TECLADO, 10);

        assertEquals(10, stock.onHand());
        assertEquals(0, stock.reserved());
        assertEquals(10, stock.available());
    }

    @Test
    @DisplayName("reservar reduce lo disponible pero no lo que hay en almacén")
    void reservingDoesNotRemoveUnitsFromTheWarehouse() {
        var stock = StockItem.of(TECLADO, 10).reserve(3);

        assertEquals(10, stock.onHand(), "las unidades siguen físicamente ahí");
        assertEquals(3, stock.reserved());
        assertEquals(7, stock.available(), "pero ya no se pueden vender");
    }

    @Test
    @DisplayName("no se puede reservar más de lo disponible")
    void cannotReserveMoreThanAvailable() {
        var stock = StockItem.of(TECLADO, 5).reserve(4);

        var exception = assertThrows(
                InsufficientStockException.class, () -> stock.reserve(2));

        assertEquals(2, exception.requested());
        assertEquals(1, exception.available());
    }

    @Test
    @DisplayName("confirmar NO cambia lo disponible: ya se había descontado al reservar")
    void confirmingDoesNotChangeAvailability() {
        var reservado = StockItem.of(TECLADO, 10).reserve(3);
        var confirmado = reservado.confirm(3);

        assertEquals(7, reservado.available());
        assertEquals(7, confirmado.available(), "confirmar no vende nada nuevo");

        // Lo que sí cambia es el almacén: esas tres unidades ya han salido.
        assertEquals(7, confirmado.onHand());
        assertEquals(0, confirmado.reserved());
    }

    @Test
    @DisplayName("liberar devuelve las unidades al inventario")
    void releasingGivesUnitsBack() {
        var stock = StockItem.of(TECLADO, 10).reserve(4).release(4);

        assertEquals(10, stock.available());
        assertEquals(10, stock.onHand(), "nunca llegaron a salir del almacén");
    }

    @Test
    @DisplayName("no se puede liberar ni confirmar más de lo reservado")
    void cannotReleaseOrConfirmMoreThanReserved() {
        var stock = StockItem.of(TECLADO, 10).reserve(2);

        assertThrows(IllegalStateException.class, () -> stock.release(3));
        assertThrows(IllegalStateException.class, () -> stock.confirm(3));
    }

    @Test
    @DisplayName("el invariante reserved <= onHand se comprueba al construir")
    void reservedCanNeverExceedOnHand() {
        // Si este estado fuera construible, ya se habría vendido algo que no existe. Se
        // comprueba en el constructor compacto, que es el único punto por el que pasan TODAS
        // las construcciones, incluidas las que haga un adaptador al leer de la base de datos.
        assertThrows(IllegalStateException.class, () -> new StockItem(TECLADO, 2, 5));
    }

    @Test
    @DisplayName("las cantidades tienen que ser positivas")
    void quantitiesMustBePositive() {
        var stock = StockItem.of(TECLADO, 10);

        assertThrows(IllegalArgumentException.class, () -> stock.reserve(0));
        assertThrows(IllegalArgumentException.class, () -> stock.reserve(-1));
        assertThrows(IllegalArgumentException.class, () -> new StockItem(TECLADO, -1, 0));
    }

    @Test
    @DisplayName("reponer añade unidades sin tocar las reservas en curso")
    void restockingLeavesReservationsAlone() {
        var stock = StockItem.of(TECLADO, 2).reserve(2).restock(8);

        assertEquals(10, stock.onHand());
        assertEquals(2, stock.reserved(), "las compras en curso siguen su camino");
        assertEquals(8, stock.available());
    }
}
