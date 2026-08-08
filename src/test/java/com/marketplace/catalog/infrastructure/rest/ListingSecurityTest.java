package com.marketplace.catalog.infrastructure.rest;

import com.marketplace.catalog.application.ListingCatalog;
import com.marketplace.catalog.domain.ListingId;
import com.marketplace.shared.domain.Money;
import com.marketplace.shared.domain.SellerId;
import com.marketplace.support.DatabaseCleaner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Los tres niveles de control de acceso, comprobados por separado.
 *
 * <pre>
 *   autenticación        ¿quién eres?               401   ← lo da OIDC
 *   autorización por rol ¿qué tipo de usuario eres? 403   ← lo da @RolesAllowed
 *   autorización por recurso ¿es TUYO?              403   ← lo damos nosotros
 * </pre>
 *
 * <p>El tercero es el que ninguna anotación cubre y el que encabeza el OWASP API Security Top 10
 * (BOLA). Su lógica ya está probada sin Quarkus en {@code ListingCatalogTest}; lo que se comprueba
 * aquí es que llegue al cliente traducida correctamente y sin filtrar datos internos.
 *
 * <p>No hay {@code @TestSecurity} en la clase, a propósito: cada test declara su identidad —o la
 * ausencia de ella— porque justamente eso es lo que está bajo prueba.
 */
@QuarkusTest
@DisplayName("Seguridad del catálogo")
class ListingSecurityTest {

    private static final String VENDEDORA = "11111111-1111-1111-1111-111111111111";
    private static final String RIVAL = "22222222-2222-2222-2222-222222222222";

    @Inject
    DatabaseCleaner database;

    @Inject
    ListingCatalog catalog;

    @BeforeEach
    void setUp() {
        database.clear();
    }

    /**
     * Crea la publicación por código y no por HTTP a propósito: las anotaciones de identidad son
     * por método, así que no se puede cambiar de usuario a mitad de un test. Preparar el estado
     * por el caso de uso y atacar por HTTP deja clarísimo qué es montaje y qué es aserción.
     */
    private ListingId listingDe(String sellerId) {
        return catalog.createProduct(
                        SellerId.of(sellerId), "Teclado", Money.of("25.00", "EUR"), 10)
                .id();
    }

    // ------------------------------------------------------------------ 401

    @Test
    @DisplayName("sin token, escribir da 401")
    void anonymousCannotWrite() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        { "title": "Teclado", "amount": "25.00", "currency": "EUR", "stock": 1 }
                        """)
                .when().post("/listings/products")
                .then().statusCode(401);
    }

    @Test
    @DisplayName("el 401 llega ANTES que la validación del cuerpo")
    void authenticationRunsBeforeValidation() {
        // El cuerpo está mal formado de varias maneras. Si la validación corriera primero, la
        // respuesta sería 400 y un anónimo podría sondear la API leyendo mensajes de error.
        given()
                .contentType(ContentType.JSON)
                .body("""
                        { "title": "", "amount": "no-es-un-numero", "stock": -5 }
                        """)
                .when().post("/listings/products")
                .then().statusCode(401);
    }

    @Test
    @DisplayName("sin token, leer el catálogo público sigue funcionando")
    void anonymousCanRead() {
        given().when().get("/listings").then().statusCode(200);
    }

    // ------------------------------------------------------------------ 403 por rol

    @Test
    @DisplayName("un comprador autenticado no puede publicar")
    @TestSecurity(user = "comprador", roles = "buyer")
    @OidcSecurity(claims = @Claim(key = "sub", value = RIVAL))
    void buyerCannotCreate() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        { "title": "Teclado", "amount": "25.00", "currency": "EUR", "stock": 1 }
                        """)
                .when().post("/listings/products")
                .then().statusCode(403);
    }

    // -------------------------------------------------------- 403 por propiedad (BOLA)

    @Test
    @DisplayName("un vendedor no puede archivar la publicación de otro")
    @TestSecurity(user = "rival", roles = "seller")
    @OidcSecurity(claims = @Claim(key = "sub", value = RIVAL))
    void sellerCannotArchiveSomeoneElsesListing() {
        var ajena = listingDe(VENDEDORA);

        given().when().post("/listings/{id}/archive", ajena.toString())
                .then()
                .statusCode(403)
                .contentType("application/problem+json")
                .body("type", containsString("not-the-owner"));
    }

    @Test
    @DisplayName("tener el rol seller no basta: hay que ser EL vendedor")
    @TestSecurity(user = "rival", roles = "seller")
    @OidcSecurity(claims = @Claim(key = "sub", value = RIVAL))
    void roleAloneIsNotEnough() {
        var propia = listingDe(RIVAL);
        var ajena = listingDe(VENDEDORA);

        // Mismo token, mismo rol, mismo endpoint. Lo único que cambia es de quién es el recurso.
        given().when().post("/listings/{id}/publish", propia.toString())
                .then().statusCode(200);

        given().when().post("/listings/{id}/publish", ajena.toString())
                .then().statusCode(403);
    }

    @Test
    @DisplayName("el 403 no filtra el identificador del solicitante")
    @TestSecurity(user = "rival", roles = "seller")
    @OidcSecurity(claims = @Claim(key = "sub", value = RIVAL))
    void forbiddenResponseLeaksNothing() {
        var ajena = listingDe(VENDEDORA);

        // El mensaje interno de la excepción sí lleva quién intentó qué, porque hace falta para
        // investigar. Lo que sale por HTTP debe decir lo mínimo: quien ataca ya sabe con qué
        // cuenta va, y confirmárselo solo le informa de cómo trabaja el sistema por dentro.
        given().when().post("/listings/{id}/archive", ajena.toString())
                .then()
                .statusCode(403)
                .body("detail", not(containsString(RIVAL)))
                .body("detail", not(containsString(VENDEDORA)));
    }

    @Test
    @DisplayName("sobre una publicación inexistente responde 404, no 403")
    @TestSecurity(user = "rival", roles = "seller")
    @OidcSecurity(claims = @Claim(key = "sub", value = RIVAL))
    void missingListingIsNotFound() {
        given().when().post("/listings/{id}/archive", UUID.randomUUID().toString())
                .then().statusCode(404);
    }

    // ------------------------------------------------------------------ el token

    @Test
    @DisplayName("el sellerId sale del sub del token, no de lo que mande el cliente")
    @TestSecurity(user = "vendedora", roles = "seller")
    @OidcSecurity(claims = @Claim(key = "sub", value = VENDEDORA))
    void sellerIdComesFromSubClaim() {
        // Se intenta colar un sellerId ajeno en el cuerpo: el DTO no tiene ese campo, así que
        // Jackson lo ignora y el vendedor acaba siendo el del token. Es la razón por la que
        // CreateProductRequest nunca tuvo ese campo.
        String id = given()
                .contentType(ContentType.JSON)
                .body("""
                        { "title": "Teclado", "amount": "25.00", "currency": "EUR",
                          "stock": 1, "sellerId": "%s" }
                        """.formatted(RIVAL))
                .when().post("/listings/products")
                .then().statusCode(201)
                .extract().path("id");

        given().when().get("/listings/{id}", id)
                .then().statusCode(200)
                .body("sellerId", equalTo(VENDEDORA));
    }
}
