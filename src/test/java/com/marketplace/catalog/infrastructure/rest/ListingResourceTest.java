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

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Tests del adaptador HTTP con peticiones reales contra el servidor.
 *
 * <p>{@code @QuarkusTest} arranca la aplicación en un puerto real y RestAssured le hace
 * peticiones HTTP de verdad: se ejercitan el enrutado, la deserialización del cuerpo, los
 * códigos de estado y las cabeceras. Es un test de integración, no unitario.
 *
 * <p>Ojo con el estado compartido: el repositorio es {@code @ApplicationScoped}, una única
 * instancia para toda la aplicación, así que los datos sobreviven de un test al siguiente. De
 * ahí el {@code clear()} en {@code @BeforeEach}. En el módulo 3, con base de datos real, este
 * problema se resuelve con transacciones que se deshacen.
 */
@QuarkusTest
@DisplayName("ListingResource")
/*
 * Identidad por defecto de toda la clase. @TestSecurity construye la SecurityIdentity
 * directamente, sin token ni Keycloak, y @OidcSecurity permite fijar los claims: hace falta
 * porque el código convierte `sub` en un SellerId y eso exige un UUID.
 *
 * Como son anotaciones, el sub tiene que ser una constante de compilación. De ahí que el
 * vendedor deje de generarse por test.
 */
@TestSecurity(user = "vendedora", roles = "seller")
@OidcSecurity(claims = @Claim(key = "sub", value = ListingResourceTest.VENDEDORA))
class ListingResourceTest {

    static final String VENDEDORA = "11111111-1111-1111-1111-111111111111";

    @Inject
    DatabaseCleaner database;

    private String sellerId;

    @BeforeEach
    void setUp() {
        database.clear();
        sellerId = VENDEDORA;
    }

    private String createPublishedProduct(String title, int stock) {
        String id = given()
                .contentType(ContentType.JSON)
                .body("""
                        { "title": "%s", "amount": "25.00", "currency": "EUR", "stock": %d }
                        """.formatted(title, stock))
                .when().post("/listings/products")
                .then().statusCode(201)
                .extract().path("id");

        given().when().post("/listings/{id}/publish", id).then().statusCode(200);
        return id;
    }

    @Nested
    @DisplayName("creación")
    @TestSecurity(user = "vendedora", roles = "seller")
    @OidcSecurity(claims = @Claim(key = "sub", value = ListingResourceTest.VENDEDORA))
    class Creation {

        @Test
        @DisplayName("POST /listings/products devuelve 201 con cabecera Location")
        void createsProduct() {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {
                              "title": "Teclado mecánico",
                              "amount": "25.00",
                              "currency": "EUR",
                              "stock": 40
                            }
                            """)
                    .when().post("/listings/products")
                    .then()
                    .statusCode(201)
                    // Location permite al cliente seguir el recurso sin componer la URL a mano.
                    .header("Location", startsWith("http"))
                    .header("Location", notNullValue())
                    .body("id", notNullValue())
                    .body("type", equalTo("PRODUCT"))
                    .body("status", equalTo("DRAFT"))
                    .body("availableUnits", equalTo(40))
                    .body("price.amount", equalTo("25.00"))
                    .body("price.currency", equalTo("EUR"))
                    .body("service", nullValue());
        }

        @Test
        @DisplayName("POST /listings/services incluye el bloque service")
        void createsService() {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {
                              "title": "Clase de guitarra",
                              "amount": "30.00",
                              "currency": "EUR",
                              "slotMinutes": 60,
                              "timeZone": "Europe/Madrid"
                            }
                            """)
                    .when().post("/listings/services")
                    .then()
                    .statusCode(201)
                    .body("type", equalTo("SERVICE"))
                    .body("service.slotMinutes", equalTo(60))
                    .body("service.timeZone", equalTo("Europe/Madrid"));
        }

        @Test
        @DisplayName("el vendedor sale del token, no del cuerpo ni de una cabecera")
        void sellerComesFromTheToken() {
            String id = given()
                    .contentType(ContentType.JSON)
                    .body("""
                            { "title": "Teclado", "amount": "25.00", "currency": "EUR", "stock": 1 }
                            """)
                    .when().post("/listings/products")
                    .then().statusCode(201)
                    .extract().path("id");

            // El cuerpo no mencionaba a ningún vendedor: el sub del token es el único origen.
            given().when().get("/listings/{id}", id)
                    .then().statusCode(200)
                    .body("sellerId", equalTo(VENDEDORA));
        }

        @Test
        @DisplayName("con moneda inexistente responde 400")
        void rejectsUnknownCurrency() {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            { "title": "Teclado", "amount": "25.00", "currency": "XYZ", "stock": 1 }
                            """)
                    .when().post("/listings/products")
                    .then().statusCode(400);
        }
    }

    @Nested
    @DisplayName("lectura")
    @TestSecurity(user = "vendedora", roles = "seller")
    @OidcSecurity(claims = @Claim(key = "sub", value = ListingResourceTest.VENDEDORA))
    class Reading {

        @Test
        @DisplayName("GET /listings solo devuelve las publicaciones visibles")
        void browseReturnsOnlyVisible() {
            createPublishedProduct("Publicado", 10);

            // Este se queda en borrador: no debe aparecer.
            given().contentType(ContentType.JSON)
                    .body("""
                            { "title": "Borrador", "amount": "25.00", "currency": "EUR", "stock": 1 }
                            """)
                    .when().post("/listings/products").then().statusCode(201);

            given()
                    .when().get("/listings")
                    .then()
                    .statusCode(200)
                    .body("items", hasSize(1))
                    .body("items[0].title", equalTo("Publicado"))
                    .body("totalItems", equalTo(1))
                    .body("page", equalTo(0))
                    .body("hasNext", equalTo(false));
        }

        @Test
        @DisplayName("GET /listings?seller= incluye también los borradores del vendedor")
        void sellerViewIncludesDrafts() {
            createPublishedProduct("Publicado", 10);
            given().contentType(ContentType.JSON)
                    .body("""
                            { "title": "Borrador", "amount": "25.00", "currency": "EUR", "stock": 1 }
                            """)
                    .when().post("/listings/products").then().statusCode(201);

            given()
                    .queryParam("seller", sellerId)
                    .when().get("/listings")
                    .then().statusCode(200).body("items", hasSize(2)).body("totalItems", equalTo(2));
        }

        @Test
        @DisplayName("GET /listings/{id} con un id que no es UUID responde 400")
        void rejectsMalformedId() {
            given().when().get("/listings/no-soy-un-uuid").then().statusCode(400);
        }
    }

    @Nested
    @DisplayName("ciclo de vida")
    @TestSecurity(user = "vendedora", roles = "seller")
    @OidcSecurity(claims = @Claim(key = "sub", value = ListingResourceTest.VENDEDORA))
    class Lifecycle {

        @Test
        @DisplayName("publish, pause y archive cambian el estado")
        void transitionsChangeStatus() {
            String id = createPublishedProduct("Teclado", 10);

            given().when().post("/listings/{id}/pause", id)
                    .then().statusCode(200).body("status", equalTo("PAUSED"));

            given().when().post("/listings/{id}/archive", id)
                    .then().statusCode(200).body("status", equalTo("ARCHIVED"));

            // Archivada: deja de aparecer en el catálogo público.
            given().when().get("/listings").then().body("items", hasSize(0)).body("totalItems", equalTo(0));
        }
    }

    @Nested
    @DisplayName("disponibilidad")
    @TestSecurity(user = "vendedora", roles = "seller")
    @OidcSecurity(claims = @Claim(key = "sub", value = ListingResourceTest.VENDEDORA))
    class Availability {

        @Test
        @DisplayName("devuelve el total cuando hay stock suficiente")
        void reportsTotal() {
            String id = createPublishedProduct("Teclado", 10);

            given()
                    .queryParam("quantity", 3)
                    .when().get("/listings/{id}/availability", id)
                    .then()
                    .statusCode(200)
                    .body("fulfillable", equalTo(true))
                    .body("total.amount", equalTo("75.00"))
                    .body("reason", nullValue());
        }

        @Test
        @DisplayName("informa del motivo y de cuánto queda cuando no alcanza")
        void reportsReasonWhenInsufficient() {
            String id = createPublishedProduct("Teclado", 2);

            given()
                    .queryParam("quantity", 5)
                    .when().get("/listings/{id}/availability", id)
                    .then()
                    .statusCode(200)
                    .body("fulfillable", equalTo(false))
                    .body("reason", equalTo("INSUFFICIENT_AVAILABILITY"))
                    .body("available", equalTo(2))
                    .body("total", nullValue());
        }

        @Test
        @DisplayName("quantity por defecto es 1")
        void defaultsToOne() {
            String id = createPublishedProduct("Teclado", 10);

            given()
                    .when().get("/listings/{id}/availability", id)
                    .then().statusCode(200)
                    .body("fulfillable", equalTo(true))
                    .body("total.amount", equalTo("25.00"));
        }
    }

    @Nested
    @DisplayName("errores")
    @TestSecurity(user = "vendedora", roles = "seller")
    @OidcSecurity(claims = @Claim(key = "sub", value = ListingResourceTest.VENDEDORA))
    class ErrorHandling {

        @Test
        @DisplayName("una publicación inexistente responde 404 en formato RFC 7807")
        void missingListingReturns404() {
            String id = UUID.randomUUID().toString();

            given()
                    .when().get("/listings/{id}", id)
                    .then()
                    .statusCode(404)
                    // El media type distingue "aquí tienes el recurso" de "aquí tienes el motivo".
                    .contentType("application/problem+json")
                    .body("type", equalTo("https://marketplace.local/problems/listing-not-found"))
                    .body("title", equalTo("Listing not found"))
                    .body("status", equalTo(404))
                    .body("detail", containsString(id))
                    .body("instance", equalTo("/listings/" + id));
        }

        @Test
        @DisplayName("una transición ilegal responde 409 Conflict, no 400")
        void illegalTransitionReturns409() {
            String id = createPublishedProduct("Teclado", 10);
            given().when().post("/listings/{id}/archive", id).then().statusCode(200);

            // La petición es impecable; lo que no encaja es el estado del recurso.
            given()
                    .when().post("/listings/{id}/publish", id)
                    .then()
                    .statusCode(409)
                    .contentType("application/problem+json")
                    .body("type", equalTo(
                            "https://marketplace.local/problems/invalid-state-transition"));
        }

        @Test
        @DisplayName("un invariante de dominio roto responde 400 en el mismo formato")
        void domainInvariantReturns400() {
            // Precio cero: el @Pattern lo acepta, pero ProductListing exige precio positivo.
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            { "title": "Gratis", "amount": "0.00", "currency": "EUR", "stock": 1 }
                            """)
                    .when().post("/listings/products")
                    .then()
                    .statusCode(400)
                    .contentType("application/problem+json")
                    .body("type", equalTo("https://marketplace.local/problems/invalid-request"));
        }

        @Test
        @DisplayName("los errores de validación usan el mismo formato que los demás")
        void validationErrorsShareTheSameFormat() {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            { "title": "", "amount": "25.00", "currency": "EUR", "stock": 1 }
                            """)
                    .when().post("/listings/products")
                    .then()
                    .statusCode(400)
                    .contentType("application/problem+json")
                    .body("type", equalTo("https://marketplace.local/problems/validation-failed"))
                    .body("status", equalTo(400))
                    .body("violations[0].field", equalTo("title"))
                    .body("violations[0].message", equalTo("title is required"));
        }
    }
}
