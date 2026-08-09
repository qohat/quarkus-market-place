package com.marketplace.inventory.infrastructure.persistence;

import com.marketplace.catalog.domain.ListingId;
import com.marketplace.inventory.domain.BuyerId;
import com.marketplace.inventory.domain.SlotAlreadyBookedException;
import com.marketplace.support.DatabaseCleaner;
import com.marketplace.support.TransactionalRunner;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * El otro problema de concurrencia: dos personas queriendo la misma hora.
 *
 * <p>Lo que se prueba aquí no es código nuestro, sino que hemos <strong>delegado bien</strong>:
 * la restricción {@code EXCLUDE USING gist} de PostgreSQL es la que impide el solapamiento, y
 * estos tests comprueban que efectivamente lo hace y que el error llega traducido a un concepto
 * de negocio.
 */
@QuarkusTest
@DisplayName("Reserva de franjas horarias")
class BookingConcurrencyTest {

    private static final int COMPRADORES = 100;

    @Inject
    BookingRepository bookings;

    @Inject
    DatabaseCleaner database;

    @Inject
    TransactionalRunner tx;

    private Instant martesALas10;

    @BeforeEach
    void setUp() {
        database.clear();
        tx.run(() -> bookings.countFor(ListingId.newId())); // calienta la conexión
        martesALas10 = Instant.now().plus(Duration.ofDays(7)).truncatedTo(ChronoUnit.HOURS);
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


    private BuyerId comprador() {
        return new BuyerId(UUID.randomUUID());
    }

    @Test
    @DisplayName("dos reservas de la misma franja: la segunda se rechaza")
    void overlappingSlotIsRejected() {
        var clase = ListingId.newId();
        var fin = martesALas10.plus(Duration.ofHours(1));

        tx.run(() -> bookings.book(clase, comprador(), martesALas10, fin));

        assertThrows(SlotAlreadyBookedException.class,
                () -> tx.run(() -> bookings.book(clase, comprador(), martesALas10, fin)));
    }

    @Test
    @DisplayName("un solapamiento parcial también se rechaza")
    void partialOverlapIsRejected() {
        var clase = ListingId.newId();
        tx.run(() -> bookings.book(
                clase, comprador(), martesALas10, martesALas10.plus(Duration.ofHours(2))));

        // De 11:00 a 12:00 pisa la segunda mitad de la reserva anterior. Escribir esta
        // comprobación a mano es donde se cuelan los errores de comparación de fechas; el
        // operador && de PostgreSQL no se equivoca.
        assertThrows(SlotAlreadyBookedException.class, () -> tx.run(() -> bookings.book(
                clase, comprador(),
                martesALas10.plus(Duration.ofHours(1)),
                martesALas10.plus(Duration.ofHours(2)))));
    }

    @Test
    @DisplayName("dos citas consecutivas SÍ caben: el intervalo es [inicio, fin)")
    void backToBackSlotsAreAllowed() {
        var clase = ListingId.newId();
        var alas11 = martesALas10.plus(Duration.ofHours(1));
        var alas12 = martesALas10.plus(Duration.ofHours(2));

        tx.run(() -> bookings.book(clase, comprador(), martesALas10, alas11));
        tx.run(() -> bookings.book(clase, comprador(), alas11, alas12));

        // Si el intervalo fuera cerrado por la derecha, 10-11 y 11-12 se considerarían solapadas
        // y un profesor no podría encadenar dos clases seguidas. El '[)' de la migración es lo
        // que lo hace posible.
        assertEquals(2, tx.call(() -> bookings.countFor(clase)));
    }

    @Test
    @DisplayName("la misma franja en OTRA publicación no molesta")
    void sameSlotOnAnotherListingIsFine() {
        var guitarra = ListingId.newId();
        var piano = ListingId.newId();
        var fin = martesALas10.plus(Duration.ofHours(1));

        tx.run(() -> bookings.book(guitarra, comprador(), martesALas10, fin));
        tx.run(() -> bookings.book(piano, comprador(), martesALas10, fin));

        // El `listing_id WITH =` de la restricción es lo que acota el solapamiento a cada
        // publicación. Sin él, una reserva bloquearía esa hora en todo el marketplace.
        assertEquals(1, tx.call(() -> bookings.countFor(guitarra)));
        assertEquals(1, tx.call(() -> bookings.countFor(piano)));
    }

    @Test
    @DisplayName("100 compradores simultáneos por la misma hora: reserva exactamente 1")
    void concurrentBookingsForTheSameSlot() {
        var clase = ListingId.newId();
        var fin = martesALas10.plus(Duration.ofHours(1));

        var exitos = new AtomicInteger();
        var rechazos = new AtomicInteger();
        var salida = new CountDownLatch(1);
        var terminados = new CountDownLatch(COMPRADORES);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, COMPRADORES).forEach(i -> pool.submit(() -> {
                try {
                    salida.await();
                    tx.run(() -> bookings.book(clase, comprador(), martesALas10, fin));
                    exitos.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException rechazado) {
                    rechazos.incrementAndGet();
                } finally {
                    terminados.countDown();
                }
            }));
            salida.countDown();
            if (!terminados.await(2, TimeUnit.MINUTES)) {
                throw new IllegalStateException("las reservas no terminaron a tiempo");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }

        // Sin consultar antes de insertar, sin bloquear nada y sin una línea de coordinación en
        // Java. La garantía la da la restricción de la base de datos.
        assertEquals(1, exitos.get(), "se reservó la misma hora más de una vez");
        assertEquals(COMPRADORES - 1, rechazos.get());
        assertEquals(1, tx.call(() -> bookings.countFor(clase)));

        System.out.printf("  franjas: %d reserva · %d rechazos%n", exitos.get(), rechazos.get());
    }
}
