package com.marketplace.catalog.infrastructure.rest;

import com.marketplace.support.DatabaseCleaner;
import com.marketplace.shared.domain.SellerId;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validación de entrada con Bean Validation.
 *
 * <p>El test que de verdad importa aquí es {@code reportsEveryViolationAtOnce}: una validación
 * escrita a mano aborta en el primer error, así que el cliente arregla un campo, reintenta,
 * descubre el siguiente, y así sucesivamente. Bean Validation recorre el objeto entero y
 * devuelve todas las infracciones de una sola vez.
 */
@QuarkusTest
@DisplayName("Validación de entrada")
class ListingValidationTest {

    /*
     * La validación solo se ejercita si la petición pasa antes el control de acceso: sin token,
     * todo esto respondería 401 y nunca llegaría a validarse nada. Ese orden —seguridad primero,
     * validación después— es deliberado y está probado en ListingSecurityTest.
     */
    static final String VENDEDORA = "11111111-1111-1111-1111-111111111111";

    @Inject
    DatabaseCleaner database;

    private String sellerId;

    @BeforeEach
    void setUp() {
        database.clear();
        sellerId = SellerId.newId().toString();
    }

    @Nested
    @DisplayName("cuerpo de la petición")
    @TestSecurity(user = "vendedora", roles = "seller")
    @OidcSecurity(claims = @Claim(key = "sub", value = ListingValidationTest.VENDEDORA))
    class RequestBody {

        @Test
        @DisplayName("informa de TODAS las infracciones a la vez, no solo de la primera")
        void reportsEveryViolationAtOnce() {
            // title vacío, amount con letras, currency en minúsculas y stock negativo:
            // cuatro problemas independientes en una sola petición.
            List<String> messages = given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {
                              "title": "",
                              "amount": "veinticinco euros",
                              "currency": "eur",
                              "stock": -5
                            }
                            """)
                    .when().post("/listings/products")
                    .then()
                    .statusCode(400)
                    .extract().jsonPath().getList("violations.message", String.class);

            assertTrue(messages.size() >= 4,
                    "se esperaban al menos 4 infracciones, llegaron " + messages.size()
                            + ": " + messages);
            assertTrue(messages.contains("title is required"), messages.toString());
            assertTrue(messages.contains("stock cannot be negative"), messages.toString());
        }

        @Test
        @DisplayName("un cuerpo vacío falla por cada campo obligatorio")
        void emptyBodyFailsOnEveryRequiredField() {
            given()
                    .contentType(ContentType.JSON)
                    .body("{}")
                    .when().post("/listings/products")
                    .then()
                    .statusCode(400)
                    .body("violations", hasSize(greaterThanOrEqualTo(3)));
        }

        @Test
        @DisplayName("rechaza un importe que no es decimal")
        void rejectsNonDecimalAmount() {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            { "title": "Teclado", "amount": "25,00", "currency": "EUR", "stock": 1 }
                            """)
                    .when().post("/listings/products")
                    .then()
                    .statusCode(400)
                    // Coma decimal: válida en español, no en un payload JSON.
                    .body("violations.message", hasItem(
                            "amount must be a decimal number, e.g. \"25.00\""));
        }

        @Test
        @DisplayName("rechaza una franja horaria mayor que un día")
        void rejectsOverlongSlot() {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {
                              "title": "Retiro de una semana",
                              "amount": "300.00", "currency": "EUR",
                              "slotMinutes": 10080,
                              "timeZone": "Europe/Madrid"
                            }
                            """)
                    .when().post("/listings/services")
                    .then()
                    .statusCode(400)
                    .body("violations.message", hasItem(
                            "slotMinutes cannot exceed 1440 (24 hours)"));
        }
    }

    @Nested
    @DisplayName("cabeceras y parámetros")
    @TestSecurity(user = "vendedora", roles = "seller")
    @OidcSecurity(claims = @Claim(key = "sub", value = ListingValidationTest.VENDEDORA))
    class HeadersAndParams {

        @Test
        @DisplayName("la vieja cabecera X-Seller-Id ya no tiene ningún efecto")
        void sellerHeaderIsIgnored() {
            // Hasta el módulo 5 esta cabecera decidía de quién era la publicación, lo que
            // permitía a cualquiera publicar en nombre de otro. Ahora el vendedor sale del
            // token y la cabecera es texto decorativo. El test existe para que siga siéndolo:
            // si alguien la resucitara, el agujero volvería sin que nada más se rompiera.
            String id = given()
                    .contentType(ContentType.JSON)
                    .header("X-Seller-Id", "99999999-9999-9999-9999-999999999999")
                    .body("""
                            { "title": "Teclado", "amount": "25.00", "currency": "EUR", "stock": 1 }
                            """)
                    .when().post("/listings/products")
                    .then().statusCode(201)
                    .extract().path("id");

            given().when().get("/listings/{id}", id)
                    .then().statusCode(200)
                    .body("sellerId", org.hamcrest.Matchers.equalTo(VENDEDORA));
        }

        @Test
        @DisplayName("rechaza quantity igual a cero o negativa")
        void rejectsNonPositiveQuantity() {
            given()
                    .queryParam("quantity", 0)
                    .when().get("/listings/{id}/availability", UUID.randomUUID().toString())
                    .then().statusCode(400);

            given()
                    .queryParam("quantity", -3)
                    .when().get("/listings/{id}/availability", UUID.randomUUID().toString())
                    .then().statusCode(400);
        }
    }

    @Nested
    @DisplayName("lo que Bean Validation NO puede comprobar")
    @TestSecurity(user = "vendedora", roles = "seller")
    @OidcSecurity(claims = @Claim(key = "sub", value = ListingValidationTest.VENDEDORA))
    class BeyondSyntax {

        @Test
        @DisplayName("una moneda con formato válido pero inexistente la rechaza el dominio")
        void wellFormedButUnknownCurrency() {
            // "XYZ" pasa el @Pattern [A-Z]{3} sin problema: sintácticamente es impecable.
            // Que no exista en ISO 4217 solo lo sabe Currency.getInstance, al construirla.
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            { "title": "Teclado", "amount": "25.00", "currency": "XYZ", "stock": 1 }
                            """)
                    .when().post("/listings/products")
                    .then().statusCode(400);
        }

        @Test
        @DisplayName("una zona con formato válido pero inexistente la rechaza el dominio")
        void wellFormedButUnknownTimeZone() {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {
                              "title": "Clase", "amount": "30.00", "currency": "EUR",
                              "slotMinutes": 60, "timeZone": "Europe/Atlantis"
                            }
                            """)
                    .when().post("/listings/services")
                    .then().statusCode(400);
        }

        @Test
        @DisplayName("una escala mayor que la de la moneda la rechaza Money")
        void excessScaleRejectedByDomain() {
            // "25.005" es un decimal perfectamente formado: el @Pattern lo acepta.
            // Money lo rechaza porque el euro tiene dos decimales y redondear sería mentir.
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            { "title": "Teclado", "amount": "25.005", "currency": "EUR", "stock": 1 }
                            """)
                    .when().post("/listings/products")
                    .then().statusCode(400);
        }
    }
}
