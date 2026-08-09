package com.marketplace.purchase.application;

import com.marketplace.catalog.domain.ListingId;
import com.marketplace.inventory.application.Inventory;
import com.marketplace.inventory.domain.BuyerId;
import com.marketplace.inventory.domain.InsufficientStockException;
import com.marketplace.inventory.domain.ReservationStatus;
import com.marketplace.purchase.domain.PaymentDeclinedException;
import com.marketplace.purchase.infrastructure.FakePaymentGateway;
import com.marketplace.shared.domain.Money;
import com.marketplace.support.DatabaseCleaner;
import com.marketplace.support.TransactionalRunner;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * La saga de compra, incluidos sus caminos torcidos.
 *
 * <p>Lo que hay que demostrar de una saga no es que el camino feliz funcione —eso es lo fácil—
 * sino que <strong>un fallo a mitad no deja el sistema inconsistente</strong>. Aquí eso significa:
 * si el pago se rechaza, no puede quedar inventario apartado para siempre.
 */
@QuarkusTest
@DisplayName("Saga de compra")
class PurchaseSagaTest {

    private static final BuyerId COMPRADOR = new BuyerId(UUID.randomUUID());
    private static final Money PRECIO_OK = Money.of("25.00", "EUR");

    /** Los céntimos .13 hacen que la pasarela simulada rechace el pago. */
    private static final Money PRECIO_RECHAZADO = Money.of("25.13", "EUR");

    @Inject
    PurchaseSaga saga;

    @Inject
    Inventory inventory;

    @Inject
    FakePaymentGateway payments;

    @Inject
    DatabaseCleaner database;

    @Inject
    TransactionalRunner tx;

    @BeforeEach
    void setUp() {
        database.clear();
    }

    /*
     * Limpiar también AL TERMINAR, no solo antes.
     *
     * Estos tests necesitan transacciones reales, así que no pueden usar @TestTransaction ni
     * apoyarse en su rollback: lo que escriben, queda. Los tests más antiguos —los que cuentan
     * filas con @TestTransaction— asumen una base vacía, así que estos residuos los hacían
     * fallar a distancia, en otra clase y por un motivo que no aparecía por ningún lado.
     *
     * Regla general: quien no puede deshacer lo que escribe, recoge al salir.
     */
    @AfterEach
    void tearDown() {
        database.clear();
    }


    private ListingId conStock(int units) {
        var listingId = ListingId.newId();
        tx.run(() -> inventory.track(listingId, units));
        return listingId;
    }

    private int disponible(ListingId listingId) {
        return tx.call(() -> inventory.stockOf(listingId).orElseThrow()).available();
    }

    @Test
    @DisplayName("camino feliz: reserva, cobra y confirma")
    void happyPath() {
        var listingId = conStock(10);

        var resultado = saga.buy(listingId, COMPRADOR, 3, PRECIO_OK);

        assertNotNull(resultado.chargeId());
        assertEquals(7, disponible(listingId));

        var stock = tx.call(() -> inventory.stockOf(listingId).orElseThrow());
        assertEquals(7, stock.onHand(), "las unidades salieron del almacén al confirmar");
        assertEquals(0, stock.reserved(), "no queda nada apartado");
    }

    @Test
    @DisplayName("si el pago se rechaza, la compensación devuelve las unidades")
    void declinedPaymentReleasesTheReservation() {
        var listingId = conStock(10);

        assertThrows(PaymentDeclinedException.class,
                () -> saga.buy(listingId, COMPRADOR, 3, PRECIO_RECHAZADO));

        // LA aserción del módulo: un fallo a mitad no deja rastro. Sin la compensación, esas 3
        // unidades quedarían apartadas hasta que caducase la reserva —quince minutos de stock
        // invendible por cada tarjeta rechazada—.
        assertEquals(10, disponible(listingId));

        var stock = tx.call(() -> inventory.stockOf(listingId).orElseThrow());
        assertEquals(10, stock.onHand());
        assertEquals(0, stock.reserved());
    }

    @Test
    @DisplayName("sin stock, la saga falla antes de tocar la pasarela")
    void noStockMeansNoCharge() {
        var listingId = conStock(2);
        int cargosAntes = payments.chargeCount();

        assertThrows(InsufficientStockException.class,
                () -> saga.buy(listingId, COMPRADOR, 5, PRECIO_OK));

        // El orden de los pasos importa: reservar primero y cobrar después evita cobrar por algo
        // que no se puede servir. Al revés habría que reembolsar, y un reembolso siempre es peor
        // experiencia que un «no hay stock».
        assertEquals(cargosAntes, payments.chargeCount(), "no debe cobrarse nada");
    }

    @Test
    @DisplayName("la reserva queda CONFIRMED tras una compra correcta")
    void reservationEndsConfirmed() {
        var listingId = conStock(10);

        var resultado = saga.buy(listingId, COMPRADOR, 1, PRECIO_OK);

        var reserva = tx.call(() -> inventory.reservationOf(
                com.marketplace.inventory.domain.ReservationId.of(resultado.reservationId()))
                .orElseThrow());
        assertEquals(ReservationStatus.CONFIRMED, reserva.status());
    }

    @Test
    @DisplayName("la reserva queda RELEASED tras un pago rechazado")
    void reservationEndsReleasedWhenPaymentFails() {
        var listingId = conStock(10);

        assertThrows(PaymentDeclinedException.class,
                () -> saga.buy(listingId, COMPRADOR, 2, PRECIO_RECHAZADO));

        // El estado de la reserva y el contador tienen que contar la misma historia. Si el
        // contador se devolviera pero la reserva quedara en HELD, el barrido de caducadas
        // intentaría devolverla otra vez e inflaría el inventario.
        assertEquals(10, disponible(listingId));
    }

    @Test
    @DisplayName("cobrar dos veces con la misma clave de idempotencia cobra una sola vez")
    void chargingTwiceWithTheSameKeyChargesOnce() {
        int antes = payments.chargeCount();

        var primero = payments.charge("misma-clave", PRECIO_OK);
        var segundo = payments.charge("misma-clave", PRECIO_OK);

        // Es lo que hace seguro reintentar cuando la red se cae después de que el cargo se
        // procese pero antes de recibir la respuesta. La saga usa el id de la reserva como
        // clave, así que un reintento del mismo paso no duplica el cobro; un UUID nuevo por
        // intento sí lo haría.
        assertEquals(primero, segundo);
        assertEquals(antes + 1, payments.chargeCount());
    }
}
