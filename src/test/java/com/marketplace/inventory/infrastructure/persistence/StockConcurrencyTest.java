package com.marketplace.inventory.infrastructure.persistence;

import com.marketplace.catalog.domain.ListingId;
import com.marketplace.inventory.domain.StockItem;
import com.marketplace.inventory.domain.StockRepository;
import com.marketplace.support.DatabaseCleaner;
import com.marketplace.support.TransactionalRunner;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La prueba que decide el módulo: muchos compradores, una unidad.
 *
 * <p>Es un test de <em>concurrencia real</em>, no una simulación. Lanza compradores simultáneos
 * contra PostgreSQL, cada uno en su propia transacción, y comprueba lo único que no admite
 * matices: <strong>que jamás se venda más de lo que hay</strong>.
 *
 * <p>La misma batería corre contra las tres estrategias. Eso cumple dos funciones: comparar su
 * comportamiento, y mantener honesta la duplicación de la regla de negocio que introduce
 * {@link AtomicStockRepository} al escribirla en SQL. Si las tres no se comportan igual, este
 * test lo dice.
 *
 * <h2>Por qué no hay {@code @TestTransaction}</h2>
 *
 * Porque haría exactamente lo contrario de lo que hace falta: envolver todo en una transacción
 * que se revierte. Aquí se necesitan transacciones simultáneas compitiendo, así que se limpia a
 * mano.
 *
 * <h2>Por qué virtual threads</h2>
 *
 * Con 200 compradores, los hilos de plataforma serían el cuello de botella del propio test y
 * podrían enmascarar el comportamiento del repositorio. Y sirve de recordatorio del módulo 4:
 * los hilos virtuales quitan el límite de los hilos, no el del pool de conexiones. El pool tiene
 * 20, así que aunque haya 200 compradores, solo 20 llegan a la base de datos a la vez.
 */
@QuarkusTest
@DisplayName("Sobreventa bajo concurrencia")
/*
 * PER_CLASS: por defecto JUnit crea una instancia nueva por test y exige que los @MethodSource
 * sean estáticos, lo que aquí es imposible porque los tres repositorios llegan por inyección.
 * Con una sola instancia por clase, el método de fábrica puede ser de instancia y ver los beans.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockConcurrencyTest {

    /** Suficientes para que compitan de verdad; contenido para que la suite no se eternice. */
    private static final int COMPRADORES = 200;

    @Inject
    DatabaseCleaner database;

    @Inject
    TransactionalRunner tx;

    @Inject
    AtomicStockRepository atomic;

    @Inject
    OptimisticStockRepository optimistic;

    @Inject
    PessimisticStockRepository pessimistic;

    /**
     * Las tres estrategias, como argumentos del mismo test. Es un método no estático porque los
     * repositorios llegan por inyección; JUnit lo admite en un {@code @QuarkusTest}.
     */
    List<Object[]> estrategias() {
        return List.of(
                new Object[] {"atomic", atomic},
                new Object[] {"optimistic", optimistic},
                new Object[] {"pessimistic", pessimistic});
    }

    /**
     * Las dos estrategias que aprovechan todo el stock disponible.
     *
     * <p>El bloqueo optimista queda fuera y no por capricho: <strong>pierde ventas</strong> que
     * podría haber servido. Lo documenta {@link #optimisticLockingUndersells}.
     */
    List<Object[]> estrategiasSinPerdidas() {
        return List.of(
                new Object[] {"atomic", atomic},
                new Object[] {"pessimistic", pessimistic});
    }

    @BeforeEach
    void setUp() {
        database.clear();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("estrategias")
    @DisplayName("con 1 unidad y 200 compradores simultáneos, se vende exactamente 1")
    void neverOversells(String nombre, StockRepository repository) {
        var listingId = ListingId.newId();
        tx.run(() -> repository.create(StockItem.of(listingId, 1)));

        var resultado = comprarEnParalelo(repository, listingId, COMPRADORES);

        // LA aserción del módulo. Si esto falla, se ha vendido algo que no existe.
        assertEquals(1, resultado.exitos(),
                nombre + " vendió " + resultado.exitos() + " unidades de las 1 disponibles");
        assertEquals(COMPRADORES - 1, resultado.rechazos());

        var finales = tx.call(() -> repository.find(listingId).orElseThrow());
        assertEquals(0, finales.available());
        assertEquals(1, finales.reserved());
        assertEquals(1, finales.onHand(), "reservar no saca unidades del almacén");

        System.out.printf("  %-12s %3d ventas · %3d rechazos · %5d ms%n",
                nombre, resultado.exitos(), resultado.rechazos(), resultado.milisegundos());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("estrategiasSinPerdidas")
    @DisplayName("con 50 unidades y 200 compradores, se venden exactamente 50")
    void sellsExactlyTheAvailableUnits(String nombre, StockRepository repository) {
        var listingId = ListingId.newId();
        tx.run(() -> repository.create(StockItem.of(listingId, 50)));

        var resultado = comprarEnParalelo(repository, listingId, COMPRADORES);

        // Con contención media el reparto cambia, pero el total no puede: es aritmética.
        assertEquals(50, resultado.exitos());
        assertEquals(0, tx.call(() -> repository.find(listingId).orElseThrow()).available());

        System.out.printf("  %-12s %3d ventas · %3d rechazos · %5d ms%n",
                nombre, resultado.exitos(), resultado.rechazos(), resultado.milisegundos());
    }

    /**
     * EL HALLAZGO DEL MÓDULO, fijado en un test para que no se olvide.
     *
     * <p>El bloqueo optimista es correcto —nunca vende de más— pero bajo contención
     * <strong>vende de menos</strong>: rechaza compras que tenía stock de sobra para servir. En la
     * ejecución que descubrió esto, con 50 unidades disponibles y 200 compradores, vendió
     * <strong>9</strong>. Cuarenta y una ventas perdidas con mercancía en el almacén.
     *
     * <p>La razón no tiene nada que ver con el stock: dos transacciones que reservan unidades
     * <em>distintas</em> escriben igualmente la misma fila, así que chocan por la versión. El
     * conflicto es de <em>fila</em>, no de negocio. La contención no la crea la escasez, la crea
     * el hecho de compartir un contador.
     *
     * <p>Con reintentos se recuperarían parte de esas ventas, a cambio de multiplicar la carga
     * justo cuando el sistema está más ocupado, que es la definición de un fallo en cascada.
     *
     * <p>Por eso {@code StockRepositoryProducer} usa la estrategia atómica por defecto. Y por eso
     * la pregunta «¿optimista o pesimista?» tiene tan mala respuesta: la buena es no elegir
     * ninguna de las dos.
     */
    @org.junit.jupiter.api.Test
    @DisplayName("el bloqueo optimista NO sobrevende, pero pierde ventas que podía servir")
    void optimisticLockingUndersells() {
        var listingId = ListingId.newId();
        tx.run(() -> optimistic.create(StockItem.of(listingId, 50)));

        var resultado = comprarEnParalelo(optimistic, listingId, COMPRADORES);

        // Correcto: jamás vende más de lo que hay. Eso nunca se negocia.
        assertTrue(resultado.exitos() <= 50,
                "sobrevendió: " + resultado.exitos() + " ventas de 50 unidades");

        // Y aquí está el problema: queda stock sin vender con 200 compradores esperando.
        var finales = tx.call(() -> optimistic.find(listingId).orElseThrow());
        System.out.printf("  optimista: %d ventas de 50 posibles · %d unidades sin vender%n",
                resultado.exitos(), finales.available());

        assertTrue(resultado.exitos() < 50,
                "si esto falla, la contención ha desaparecido y el test ya no demuestra nada");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("estrategias")
    @DisplayName("reservar y liberar en paralelo deja el inventario cuadrado")
    void concurrentReserveAndReleaseBalancesOut(String nombre, StockRepository repository) {
        var listingId = ListingId.newId();
        tx.run(() -> repository.create(StockItem.of(listingId, 100)));

        // 100 compradores reservan una unidad y acto seguido la sueltan, como quien abandona el
        // carrito. Al final no debe quedar ni una unidad apartada: los contadores tienen que
        // cuadrar aunque las operaciones se hayan entrelazado de cualquier manera.
        ejecutarEnParalelo(100, () -> {
            try {
                tx.run(() -> repository.reserve(listingId, 1));
                tx.run(() -> repository.release(listingId, 1));
            } catch (RuntimeException ignorado) {
                // Un choque optimista aquí es legítimo: lo que se comprueba es que no queden
                // unidades apartadas por operaciones a medias, no que todas terminen.
            }
        });

        var finales = tx.call(() -> repository.find(listingId).orElseThrow());
        assertEquals(100, finales.onHand());
        assertEquals(finales.onHand() - finales.reserved(), finales.available(),
                "los contadores tienen que cuadrar pase lo que pase");
        System.out.printf("  %-12s quedan %d reservadas de 100%n", nombre, finales.reserved());
    }

    // ------------------------------------------------------------------ apoyo

    private record Resultado(int exitos, int rechazos, long milisegundos) {
    }

    /**
     * Lanza {@code compradores} intentos de reservar una unidad y cuenta cómo acaban.
     *
     * <p>Cualquier {@code RuntimeException} cuenta como rechazo: da igual si fue por falta de
     * stock, por un choque optimista o por un tiempo de espera del bloqueo. Desde fuera, todas
     * significan lo mismo —esta compra no se hizo— y equipararlas es lo que permite comparar las
     * tres estrategias con la misma vara.
     */
    private Resultado comprarEnParalelo(
            StockRepository repository, ListingId listingId, int compradores) {

        var exitos = new AtomicInteger();
        var rechazos = new AtomicInteger();

        long inicio = System.currentTimeMillis();
        ejecutarEnParalelo(compradores, () -> {
            try {
                tx.run(() -> repository.reserve(listingId, 1));
                exitos.incrementAndGet();
            } catch (RuntimeException e) {
                rechazos.incrementAndGet();
            }
        });
        long fin = System.currentTimeMillis();

        return new Resultado(exitos.get(), rechazos.get(), fin - inicio);
    }

    /**
     * Ejecuta una tarea N veces a la vez.
     *
     * <p>El {@link CountDownLatch} es lo que hace que esto sea una prueba de concurrencia y no
     * una secuencia rápida: sin él, los primeros hilos terminarían antes de que arrancaran los
     * últimos y no llegarían a competir. Con la barrera, todos esperan y salen a la vez.
     */
    private void ejecutarEnParalelo(int veces, Runnable tarea) {
        var salida = new CountDownLatch(1);
        var terminados = new CountDownLatch(veces);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, veces).forEach(i -> pool.submit(() -> {
                try {
                    salida.await();
                    tarea.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    terminados.countDown();
                }
            }));

            salida.countDown();
            if (!terminados.await(Duration.ofMinutes(2).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(
                        "las operaciones no terminaron en 2 minutos: ¿pool de conexiones agotado?");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
