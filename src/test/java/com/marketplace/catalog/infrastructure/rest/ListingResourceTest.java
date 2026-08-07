package com.marketplace.catalog.infrastructure.rest;

import com.marketplace.catalog.infrastructure.InMemoryListingRepository;
import com.marketplace.shared.domain.SellerId;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
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
class ListingResourceTest {

    @Inject
    InMemoryListingRepository repository;

    private String sellerId;

    @BeforeEach
    void setUp() {
        repository.clear();
        sellerId = SellerId.newId().toString();
    }

    private String createPublishedProduct(String title, int stock) {
        String id = given()
                .contentType(ContentType.JSON)
                .header("X-Seller-Id", sellerId)
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
    class Creation {

        @Test
        @DisplayName("POST /listings/products devuelve 201 con cabecera Location")
        void createsProduct() {
            given()
                    .contentType(ContentType.JSON)
                    .header("X-Seller-Id", sellerId)
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
                    .header("X-Seller-Id", sellerId)
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
        @DisplayName("sin cabecera X-Seller-Id responde 400")
        void rejectsMissingSeller() {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            { "title": "Teclado", "amount": "25.00", "currency": "EUR", "stock": 1 }
                            """)
                    .when().post("/listings/products")
                    .then().statusCode(400);
        }

        @Test
        @DisplayName("con moneda inexistente responde 400")
        void rejectsUnknownCurrency() {
            given()
                    .contentType(ContentType.JSON)
                    .header("X-Seller-Id", sellerId)
                    .body("""
                            { "title": "Teclado", "amount": "25.00", "currency": "XYZ", "stock": 1 }
                            """)
                    .when().post("/listings/products")
                    .then().statusCode(400);
        }
    }

    @Nested
    @DisplayName("lectura")
    class Reading {

        @Test
        @DisplayName("GET /listings solo devuelve las publicaciones visibles")
        void browseReturnsOnlyVisible() {
            createPublishedProduct("Publicado", 10);

            // Este se queda en borrador: no debe aparecer.
            given().contentType(ContentType.JSON).header("X-Seller-Id", sellerId)
                    .body("""
                            { "title": "Borrador", "amount": "25.00", "currency": "EUR", "stock": 1 }
                            """)
                    .when().post("/listings/products").then().statusCode(201);

            given()
                    .when().get("/listings")
                    .then()
                    .statusCode(200)
                    .body("$", hasSize(1))
                    .body("[0].title", equalTo("Publicado"));
        }

        @Test
        @DisplayName("GET /listings?seller= incluye también los borradores del vendedor")
        void sellerViewIncludesDrafts() {
            createPublishedProduct("Publicado", 10);
            given().contentType(ContentType.JSON).header("X-Seller-Id", sellerId)
                    .body("""
                            { "title": "Borrador", "amount": "25.00", "currency": "EUR", "stock": 1 }
                            """)
                    .when().post("/listings/products").then().statusCode(201);

            given()
                    .queryParam("seller", sellerId)
                    .when().get("/listings")
                    .then().statusCode(200).body("$", hasSize(2));
        }

        @Test
        @DisplayName("GET /listings/{id} con un id que no es UUID responde 400")
        void rejectsMalformedId() {
            given().when().get("/listings/no-soy-un-uuid").then().statusCode(400);
        }
    }

    @Nested
    @DisplayName("ciclo de vida")
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
            given().when().get("/listings").then().body("$", hasSize(0));
        }
    }

    @Nested
    @DisplayName("disponibilidad")
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
    @DisplayName("errores todavía sin mapear (se arregla en el paso 2.6)")
    class PendingErrorHandling {

        @Test
        @DisplayName("una publicación inexistente responde 500, no 404")
        void missingListingReturns500ForNow() {
            // ListingNotFoundException es una excepción de dominio que nadie traduce todavía,
            // así que Quarkus la trata como error no controlado. Debería ser un 404, y este
            // test lo documenta: en el paso 2.6 añadiremos el ExceptionMapper y pasará a 404.
            given()
                    .when().get("/listings/{id}", UUID.randomUUID().toString())
                    .then().statusCode(500);
        }
    }
}
