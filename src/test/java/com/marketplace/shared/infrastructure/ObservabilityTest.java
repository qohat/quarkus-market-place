package com.marketplace.shared.infrastructure;

import com.marketplace.catalog.domain.ListingId;
import com.marketplace.inventory.application.Inventory;
import com.marketplace.purchase.application.PurchaseSaga;
import com.marketplace.purchase.domain.PaymentDeclinedException;
import com.marketplace.inventory.domain.BuyerId;
import com.marketplace.shared.domain.Money;
import com.marketplace.support.DatabaseCleaner;
import com.marketplace.support.TransactionalRunner;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Que lo que se mide, se mide bien.
 *
 * <p>Las métricas y los health checks son de las pocas cosas que nadie prueba y que solo se miran
 * cuando algo va mal — es decir, en el peor momento posible para descubrir que estaban rotas.
 */
@QuarkusTest
@DisplayName("Observabilidad")
class ObservabilityTest {

    private static final BuyerId COMPRADOR = new BuyerId(UUID.randomUUID());
    private static final Money PRECIO_OK = Money.of("25.00", "EUR");
    private static final Money PRECIO_RECHAZADO = Money.of("25.13", "EUR");

    @Inject
    PurchaseSaga saga;

    @Inject
    Inventory inventory;

    @Inject
    MeterRegistry registry;

    @Inject
    DatabaseCleaner database;

    @Inject
    TransactionalRunner tx;

    @BeforeEach
    void setUp() {
        database.clear();
    }

    @AfterEach
    void tearDown() {
        database.clear();
    }

    private ListingId conStock(int units) {
        var listingId = ListingId.newId();
        tx.run(() -> inventory.track(listingId, units));
        return listingId;
    }

    private double contador(String resultado) {
        var counter = registry.find("marketplace.payments").tag("result", resultado).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("una compra correcta incrementa el contador de cobros")
    void aSuccessfulPurchaseIsCounted() {
        double antes = contador("charged");

        saga.buy(conStock(10), COMPRADOR, 1, PRECIO_OK);

        assertEquals(antes + 1, contador("charged"));
    }

    @Test
    @DisplayName("un rechazo se cuenta aparte de un fallo técnico")
    void declinesAndFailuresAreCountedSeparately() {
        double rechazosAntes = contador("declined");

        assertThrows(PaymentDeclinedException.class,
                () -> saga.buy(conStock(10), COMPRADOR, 1, PRECIO_RECHAZADO));

        // Separarlos no es cosmético: un pico de `declined` es un problema de negocio —una
        // pasarela que endurece sus reglas, un fraude— y un pico de `unavailable` es un problema
        // técnico. Mezclarlos en un solo contador de «errores» obliga a ir a los logs para saber
        // cuál de las dos cosas está pasando.
        assertEquals(rechazosAntes + 1, contador("declined"));
    }

    @Test
    @DisplayName("las métricas se exponen en formato Prometheus")
    void metricsAreExposed() {
        saga.buy(conStock(10), COMPRADOR, 1, PRECIO_OK);

        given().when().get("/q/metrics")
                .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.containsString("marketplace_payments_total"));
    }

    @Test
    @DisplayName("las etiquetas tienen cardinalidad ACOTADA")
    void tagsHaveBoundedCardinality() {
        for (int i = 0; i < 5; i++) {
            saga.buy(conStock(10), new BuyerId(UUID.randomUUID()), 1, PRECIO_OK);
        }

        // Cinco compradores distintos, y sigue habiendo como mucho tres series temporales
        // —charged, declined, unavailable—. Si el buyerId fuera una etiqueta, habría cinco
        // series nuevas por cada cinco compradores, y en producción una por persona: es como
        // se revienta un Prometheus, y no se nota hasta que se queda sin memoria.
        long series = registry.find("marketplace.payments").counters().size();
        assertTrue(series <= 3, "demasiadas series temporales: " + series);
    }

    @Test
    @DisplayName("el health check de readiness responde con datos útiles")
    void readinessReportsUsefulData() {
        given().when().get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                // Un health check que solo dice «mal» obliga a ir a buscar por qué. Este dice
                // cuántos eventos hay pendientes y desde qué umbral preocuparse.
                .body("checks.find { it.name == 'outbox-backlog' }.data.threshold",
                        equalTo(1000));
    }

    @Test
    @DisplayName("liveness y readiness son endpoints distintos, y eso importa")
    void livenessAndReadinessAreSeparate() {
        // En Kubernetes: si falla liveness REINICIA el contenedor; si falla readiness solo lo
        // saca del balanceador. Poner una dependencia externa en liveness significa que, cuando
        // la base de datos se caiga, se reinicien todas las instancias a la vez y una incidencia
        // se convierta en una caída total.
        given().when().get("/q/health/live").then().statusCode(200);
        given().when().get("/q/health/ready").then().statusCode(200);
    }
}
