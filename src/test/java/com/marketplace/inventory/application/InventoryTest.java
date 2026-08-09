package com.marketplace.inventory.application;

import com.marketplace.catalog.domain.ListingId;
import com.marketplace.inventory.domain.BuyerId;
import com.marketplace.inventory.domain.InsufficientStockException;
import com.marketplace.inventory.domain.ReservationStatus;
import com.marketplace.support.DatabaseCleaner;
import com.marketplace.support.TransactionalRunner;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * El ciclo completo de una compra: apartar, pagar o abandonar.
 *
 * <p>Lo que se comprueba en todos ellos es la misma invariante: el contador de existencias y las
 * filas de reservas tienen que contar lo mismo, pase lo que pase por el camino.
 */
@QuarkusTest
@DisplayName("Inventario")
class InventoryTest {

    private static final BuyerId COMPRADOR = new BuyerId(UUID.randomUUID());

    @Inject
    Inventory inventory;

    @Inject
    DatabaseCleaner database;

    @Inject
    TransactionalRunner tx;

    @BeforeEach
    void setUp() {
        database.clear();
    }

    @Test
    @DisplayName("reservar aparta unidades sin sacarlas del almacén")
    void reservingHoldsUnits() {
        var listingId = ListingId.newId();
        tx.run(() -> inventory.track(listingId, 10));

        var reserva = tx.call(() -> inventory.reserve(listingId, COMPRADOR, 3));

        assertEquals(ReservationStatus.HELD, reserva.status());
        var stock = tx.call(() -> inventory.stockOf(listingId).orElseThrow());
        assertEquals(10, stock.onHand());
        assertEquals(3, stock.reserved());
        assertEquals(7, stock.available());
    }

    @Test
    @DisplayName("confirmar el pago saca las unidades del almacén")
    void confirmingRemovesUnits() {
        var listingId = ListingId.newId();
        tx.run(() -> inventory.track(listingId, 10));
        var reserva = tx.call(() -> inventory.reserve(listingId, COMPRADOR, 3));

        tx.run(() -> inventory.confirm(reserva.id()));

        var stock = tx.call(() -> inventory.stockOf(listingId).orElseThrow());
        assertEquals(7, stock.onHand(), "las unidades ya han salido");
        assertEquals(0, stock.reserved());
        assertEquals(7, stock.available(), "confirmar no cambia lo disponible");
    }

    @Test
    @DisplayName("cancelar devuelve las unidades al inventario")
    void cancellingGivesUnitsBack() {
        var listingId = ListingId.newId();
        tx.run(() -> inventory.track(listingId, 10));
        var reserva = tx.call(() -> inventory.reserve(listingId, COMPRADOR, 3));

        tx.run(() -> inventory.cancel(reserva.id()));

        var stock = tx.call(() -> inventory.stockOf(listingId).orElseThrow());
        assertEquals(10, stock.available());
        assertEquals(10, stock.onHand());
    }

    @Test
    @DisplayName("una reserva no se puede confirmar dos veces")
    void cannotConfirmTwice() {
        var listingId = ListingId.newId();
        tx.run(() -> inventory.track(listingId, 10));
        var reserva = tx.call(() -> inventory.reserve(listingId, COMPRADOR, 2));
        tx.run(() -> inventory.confirm(reserva.id()));

        // Sin esta regla, un cliente que reintenta un pago descontaría el stock dos veces.
        assertThrows(IllegalStateException.class,
                () -> tx.run(() -> inventory.confirm(reserva.id())));
    }

    @Test
    @DisplayName("no se puede reservar más de lo disponible")
    void cannotReserveMoreThanAvailable() {
        var listingId = ListingId.newId();
        tx.run(() -> inventory.track(listingId, 5));
        tx.run(() -> inventory.reserve(listingId, COMPRADOR, 4));

        assertThrows(InsufficientStockException.class,
                () -> tx.run(() -> inventory.reserve(listingId, COMPRADOR, 2)));
    }

    // ---------------------------------------------------------------- caducidad

    @Test
    @DisplayName("el barrido devuelve al inventario las reservas vencidas")
    void expiredReservationsGoBackToStock() {
        var listingId = ListingId.newId();
        tx.run(() -> inventory.track(listingId, 10));
        tx.run(() -> inventory.reserve(listingId, COMPRADOR, 4));

        // No hace falta esperar: el instante es un parámetro, así que se le pasa un futuro. Un
        // test que dependiera del reloj real sería lento y, peor, intermitente.
        var futuro = Instant.now().plus(Duration.ofHours(1));
        int liberadas = tx.call(() -> inventory.releaseExpired(futuro));

        assertEquals(1, liberadas);
        assertEquals(10, tx.call(() -> inventory.stockOf(listingId).orElseThrow()).available());
    }

    @Test
    @DisplayName("el barrido no toca las reservas que aún están vivas")
    void liveReservationsAreLeftAlone() {
        var listingId = ListingId.newId();
        tx.run(() -> inventory.track(listingId, 10));
        tx.run(() -> inventory.reserve(listingId, COMPRADOR, 4));

        int liberadas = tx.call(() -> inventory.releaseExpired(Instant.now()));

        assertEquals(0, liberadas);
        assertEquals(6, tx.call(() -> inventory.stockOf(listingId).orElseThrow()).available());
    }

    @Test
    @DisplayName("el barrido es idempotente: repetirlo no infla el inventario")
    void sweepingTwiceDoesNotDoubleRelease() {
        var listingId = ListingId.newId();
        tx.run(() -> inventory.track(listingId, 10));
        tx.run(() -> inventory.reserve(listingId, COMPRADOR, 4));
        var futuro = Instant.now().plus(Duration.ofHours(1));

        // Es lo que ocurre con varias instancias desplegadas: dos barridos a la vez. La segunda
        // pasada no debe devolver las unidades otra vez, o el inventario crecería solo.
        assertEquals(1, tx.call(() -> inventory.releaseExpired(futuro)));
        assertEquals(0, tx.call(() -> inventory.releaseExpired(futuro)));

        assertEquals(10, tx.call(() -> inventory.stockOf(listingId).orElseThrow()).available());
        assertEquals(10, tx.call(() -> inventory.stockOf(listingId).orElseThrow()).onHand());
    }

    @Test
    @DisplayName("una reserva confirmada no la toca el barrido aunque haya vencido")
    void confirmedReservationsSurviveTheSweep() {
        var listingId = ListingId.newId();
        tx.run(() -> inventory.track(listingId, 10));
        var reserva = tx.call(() -> inventory.reserve(listingId, COMPRADOR, 4));
        tx.run(() -> inventory.confirm(reserva.id()));

        // Pagó dentro de plazo; que la fecha de caducidad pase después no puede deshacer la venta.
        var futuro = Instant.now().plus(Duration.ofHours(1));
        assertEquals(0, tx.call(() -> inventory.releaseExpired(futuro)));
        assertEquals(6, tx.call(() -> inventory.stockOf(listingId).orElseThrow()).onHand());
    }
}
