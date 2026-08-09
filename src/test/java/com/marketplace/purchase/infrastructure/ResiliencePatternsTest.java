package com.marketplace.purchase.infrastructure;

import com.marketplace.purchase.domain.PaymentDeclinedException;
import com.marketplace.shared.domain.Money;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Los patrones de resiliencia, en acción.
 *
 * <p>Poner {@code @Retry} y {@code @CircuitBreaker} es fácil; lo difícil es que hagan lo que uno
 * cree. Estos tests provocan cada modo de fallo y comprueban el <strong>número real de intentos
 * que llegan a la pasarela</strong>, que es la única prueba de que un patrón está actuando.
 */
@QuarkusTest
@DisplayName("Patrones de resiliencia")
class ResiliencePatternsTest {

    private static final Money PRECIO = Money.of("25.00", "EUR");

    @Inject
    ResilientPaymentGateway resilient;

    @Inject
    FakePaymentGateway gateway;

    @BeforeEach
    void setUp() {
        // El circuit breaker guarda estado ENTRE tests: es un bean de aplicación, no de
        // petición. Sin estabilizarlo, un test que deje el circuito abierto hace fallar al
        // siguiente por un motivo que no tiene nada que ver con lo que prueba.
        esperarACircuitoCerrado();

        // El reset va DESPUÉS, y esto costó un test rojo: estabilizar el circuito exige hacer
        // llamadas reales, que el contador de intentos registra. Poniéndolo antes, el test que
        // comprueba «un rechazo no se reintenta» veía 2 intentos en vez de 1 y acusaba al
        // código de algo que había hecho su propio montaje.
        gateway.reset();
    }

    /**
     * Deja el circuito CERRADO al salir, no solo la pasarela sana.
     *
     * <p>El circuit breaker es estado de un bean de aplicación: sobrevive al test. Estos tests lo
     * abren a propósito, y un circuito abierto impide cobrar a cualquier test posterior — que
     * falla acusando a su propio código de algo que hizo este.
     *
     * <p>Es la misma lección del módulo 7 con las filas sin limpiar, aplicada al estado en
     * memoria: <strong>quien ensucia, recoge</strong>. Y aquí duele más, porque ese estado no se
     * ve en ninguna parte.
     */
    @AfterEach
    void tearDown() {
        gateway.reset();
        esperarACircuitoCerrado();
        gateway.reset();
    }

    private String clave() {
        return UUID.randomUUID().toString();
    }

    /**
     * Devuelve el circuito a un estado limpio antes de cada test.
     *
     * <p>No basta con esperar a que cierre. El circuit breaker decide sobre una VENTANA RODANTE
     * de las últimas {@code requestVolumeThreshold} llamadas —8 aquí—, así que un test que deje
     * la ventana llena de fallos hace abrir el circuito al segundo fallo del test siguiente.
     *
     * <p>Eso costó un test rojo con un diagnóstico engañoso: el de {@code @Retry} veía 2 intentos
     * en vez de 4 y parecía que el retry no reintentaba. Reintentaba: el circuito se abría a
     * mitad y cortaba los reintentos restantes, que es <strong>precisamente el comportamiento
     * correcto</strong> —Retry envuelve a CircuitBreaker, así que cada reintento pasa por él—.
     *
     * <p>Por eso hay que llenar la ventana de ÉXITOS, no solo esperar.
     */
    private void esperarACircuitoCerrado() {
        gateway.behave(FakePaymentGateway.Mode.HEALTHY);
        int exitosSeguidos = 0;
        for (int i = 0; i < 60 && exitosSeguidos < 10; i++) {
            try {
                resilient.charge(clave(), PRECIO);
                exitosSeguidos++;
            } catch (RuntimeException circuitoAunAbierto) {
                exitosSeguidos = 0;
                sleep(300);
            }
        }
    }

    // ------------------------------------------------------------------ timeout

    @Test
    @DisplayName("@Timeout corta una pasarela lenta en vez de esperarla")
    void timeoutCutsOffASlowGateway() {
        // Tres segundos de respuesta contra un timeout de dos.
        gateway.behave(FakePaymentGateway.Mode.SLOW, 3000);

        long inicio = System.currentTimeMillis();
        assertThrows(RuntimeException.class, () -> resilient.charge(clave(), PRECIO));
        long transcurrido = System.currentTimeMillis() - inicio;

        // Lo que se demuestra: la llamada NO esperó los 3 segundos. Sin timeout, cada compra
        // retendría un hilo y una conexión ese tiempo, y con suficientes compras simultáneas la
        // aplicación entera se queda sin recursos por culpa de un dependiente que ni siquiera
        // está caído — solo lento. Es el modo de fallo más traicionero que existe.
        assertTrue(transcurrido < 3000,
                "debería haber cortado antes de que la pasarela respondiera, tardó " + transcurrido + " ms");
    }

    // ------------------------------------------------------------------ retry

    @Test
    @DisplayName("@Retry reintenta los fallos TÉCNICOS")
    void retryRetriesTransientFailures() {
        gateway.behave(FakePaymentGateway.Mode.TRANSIENT_FAILURE);

        assertThrows(RuntimeException.class, () -> resilient.charge(clave(), PRECIO));

        // maxRetries = 3 significa 1 intento inicial + 3 reintentos = 4 llegadas a la pasarela.
        // Es un detalle que se confunde constantemente al configurar reintentos.
        assertEquals(4, gateway.attempts(), "1 intento + 3 reintentos");
    }

    @Test
    @DisplayName("@Retry NO reintenta un pago rechazado: es un resultado de negocio")
    void retryDoesNotRetryDeclines() {
        gateway.behave(FakePaymentGateway.Mode.DECLINE);

        assertThrows(PaymentDeclinedException.class, () -> resilient.charge(clave(), PRECIO));

        // LA aserción más importante del módulo. Un rechazo fallará igual las veces que se
        // intente: reintentarlo gasta cuota en la pasarela y puede disparar sus controles
        // antifraude. `abortOn` es lo que marca la diferencia entre las dos clases de fallo.
        assertEquals(1, gateway.attempts(), "un rechazo no se reintenta ni una vez");
    }

    @Test
    @DisplayName("un fallo transitorio que se cura se recupera solo")
    void aTransientFailureThatHealsRecovers() {
        gateway.behave(FakePaymentGateway.Mode.TRANSIENT_FAILURE);
        // La pasarela se recupera mientras el retry sigue intentándolo.
        new Thread(() -> {
            sleep(250);
            gateway.behave(FakePaymentGateway.Mode.HEALTHY);
        }).start();

        var chargeId = resilient.charge(clave(), PRECIO);

        // Esto es para lo que sirve un retry: el usuario nunca se entera de que hubo un problema.
        assertNotNull(chargeId);
        assertTrue(gateway.attempts() > 1, "hizo falta más de un intento");
    }

    // ------------------------------------------------------------ circuit breaker

    @Test
    @DisplayName("@CircuitBreaker abre y DEJA DE LLAMAR a la pasarela caída")
    void circuitBreakerStopsCallingABrokenGateway() {
        gateway.behave(FakePaymentGateway.Mode.TRANSIENT_FAILURE);

        // Se machaca hasta superar el umbral (8 llamadas, 50 % de fallos).
        for (int i = 0; i < 10; i++) {
            try {
                resilient.charge(clave(), PRECIO);
            } catch (RuntimeException esperado) {
                // acumulando fallos
            }
        }
        int intentosAlAbrir = gateway.attempts();

        // Con el circuito abierto, la llamada falla AL INSTANTE y sin tocar la pasarela.
        assertThrows(CircuitBreakerOpenException.class,
                () -> resilient.charge(clave(), PRECIO));

        // La prueba de que está actuando: la pasarela no recibió ni una llamada más.
        assertEquals(intentosAlAbrir, gateway.attempts(),
                "con el circuito abierto no debe llegar ninguna llamada");
    }

    @Test
    @DisplayName("el circuito abierto falla rápido, que es todo el objetivo")
    void anOpenCircuitFailsFast() {
        gateway.behave(FakePaymentGateway.Mode.TRANSIENT_FAILURE);
        for (int i = 0; i < 10; i++) {
            try {
                resilient.charge(clave(), PRECIO);
            } catch (RuntimeException esperado) {
                // acumulando fallos
            }
        }

        long inicio = System.currentTimeMillis();
        assertThrows(CircuitBreakerOpenException.class, () -> resilient.charge(clave(), PRECIO));
        long transcurrido = System.currentTimeMillis() - inicio;

        // Aquí está el valor real del patrón: no protege a la pasarela, TE PROTEGE A TI. En vez
        // de esperar 2 segundos de timeout más tres reintentos por cada compra, respondes en
        // microsegundos y liberas los recursos para las peticiones que sí pueden atenderse.
        assertTrue(transcurrido < 100, "debería fallar al instante, tardó " + transcurrido + " ms");
    }

    @Test
    @DisplayName("un rechazo NO abre el circuito: no es un fallo de infraestructura")
    void declinesDoNotOpenTheCircuit() {
        gateway.behave(FakePaymentGateway.Mode.DECLINE);

        for (int i = 0; i < 12; i++) {
            assertThrows(PaymentDeclinedException.class, () -> resilient.charge(clave(), PRECIO));
        }

        // Sin `skipOn`, una racha de compradores sin fondos abriría el circuito e impediría
        // cobrar a los que SÍ tienen dinero. El circuito debe contar fallos de infraestructura,
        // jamás resultados de negocio. Es un fallo sutil y carísimo.
        gateway.behave(FakePaymentGateway.Mode.HEALTHY);
        assertNotNull(resilient.charge(clave(), PRECIO),
                "el circuito no debería haberse abierto por rechazos legítimos");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
