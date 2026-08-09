package com.marketplace.shared.infrastructure.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Control de admisión: rechazar pronto en vez de encolar.
 *
 * <p>Cierra el círculo del módulo 4, donde medimos que un servicio saturado
 * <strong>no daba ni un error</strong>: aceptaba todo y respondía en un segundo lo que costaba
 * cien milisegundos. La conclusión era que saturar en silencio es peor que fallar. Esto es fallar,
 * a propósito y pronto.
 *
 * <h2>Por qué hace falta un perfil de test propio</h2>
 *
 * El rate limiting está apagado en los tests normales: hacen ráfagas mucho más agresivas que
 * cualquier cliente real —cientos de peticiones desde un bucle— y empezarían a recibir 429 por
 * hacer justamente aquello para lo que existen. Aquí se enciende con un límite diminuto para poder
 * agotarlo en pocas peticiones.
 */
@QuarkusTest
@TestProfile(RateLimitTest.LimiteDiminuto.class)
@DisplayName("Control de admisión")
class RateLimitTest {

    /**
     * Cubo de 5 fichas sin reposición apreciable, para agotarlo en cinco peticiones en vez de en
     * cien. Un test que necesite cien peticiones para demostrar algo es un test lento y frágil.
     */
    public static class LimiteDiminuto implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "marketplace.rate-limit.enabled", "true",
                    "marketplace.rate-limit.capacity", "5",
                    "marketplace.rate-limit.refill-per-second", "1");
        }
    }

    /** Cada test usa una identidad distinta: los cubos son por cliente y persisten entre tests. */
    private String cliente() {
        return "10.0.0." + Math.abs(UUID.randomUUID().hashCode() % 250);
    }

    @Test
    @DisplayName("pasado el límite responde 429 con Retry-After")
    void tooManyRequestsGets429() {
        var ip = cliente();
        for (int i = 0; i < 5; i++) {
            given().header("X-Forwarded-For", ip).when().get("/listings").then().statusCode(200);
        }

        given().header("X-Forwarded-For", ip)
                .when().get("/listings")
                .then()
                .statusCode(429)
                // Retry-After no es decoración: sin ella, un cliente bien programado reintenta a
                // ciegas y uno mal programado reintenta en bucle — justo lo que no quieres de
                // quien ya estaba pidiendo demasiado.
                .header("Retry-After", "1")
                .contentType(ProblemDetail.MEDIA_TYPE)
                .body("type", org.hamcrest.Matchers.containsString("rate-limit-exceeded"));
    }

    @Test
    @DisplayName("el límite es POR CLIENTE: uno abusivo no afecta a los demás")
    void oneAbusiveClientDoesNotAffectOthers() {
        var abusivo = cliente();
        var educado = cliente();

        for (int i = 0; i < 6; i++) {
            given().header("X-Forwarded-For", abusivo).when().get("/listings");
        }

        // Es la propiedad que hace usable el control de admisión. Un límite global convertiría a
        // un solo cliente descontrolado —o un bot— en una denegación de servicio para todos.
        given().header("X-Forwarded-For", educado)
                .when().get("/listings")
                .then().statusCode(200);
    }

    @Test
    @DisplayName("los health checks NUNCA se limitan")
    void healthChecksAreNeverLimited() {
        var ip = cliente();
        for (int i = 0; i < 20; i++) {
            given().header("X-Forwarded-For", ip).when().get("/listings");
        }

        // Si el rate limiting alcanzara a /q/health, Kubernetes recibiría 429 al sondear un
        // servicio saturado, lo sacaría del balanceador y, con liveness, lo reiniciaría. El
        // control de admisión habría convertido una sobrecarga pasajera en una caída. Por eso
        // las rutas de sondeo van siempre exentas.
        given().header("X-Forwarded-For", ip).when().get("/q/health/ready").then().statusCode(200);
        given().header("X-Forwarded-For", ip).when().get("/q/metrics").then().statusCode(200);
    }

    @Test
    @DisplayName("el cubo se repone con el tiempo")
    void theBucketRefills() throws InterruptedException {
        var ip = cliente();
        for (int i = 0; i < 6; i++) {
            given().header("X-Forwarded-For", ip).when().get("/listings");
        }

        // Con reposición de 1/s, en dos segundos hay fichas otra vez. Es la diferencia con un
        // bloqueo: el cliente no queda castigado, solo frenado.
        Thread.sleep(2000);

        given().header("X-Forwarded-For", ip).when().get("/listings").then().statusCode(200);
    }

    @Test
    @DisplayName("tolera ráfagas, que es lo que hace un cliente legítimo")
    void burstsAreTolerated() {
        var ip = cliente();

        // Cinco peticiones seguidas y sin pausa: es lo que hace una pantalla al abrirse. Un
        // contador por ventana fija con el mismo ritmo medio las rechazaría, y además dejaría
        // pasar el doble de tráfico justo en el cambio de ventana. El cubo de fichas acumula
        // mientras el cliente está callado y le deja gastarlo de golpe.
        int correctas = 0;
        for (int i = 0; i < 5; i++) {
            if (given().header("X-Forwarded-For", ip).when().get("/listings")
                    .then().extract().statusCode() == 200) {
                correctas++;
            }
        }

        assertTrue(correctas >= 5, "una ráfaga dentro de la capacidad debe pasar entera");
    }
}
