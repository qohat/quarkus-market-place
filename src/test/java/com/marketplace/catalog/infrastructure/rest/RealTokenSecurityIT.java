package com.marketplace.catalog.infrastructure.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * Los pocos tests que usan un token de verdad, emitido por Keycloak.
 *
 * <h2>Por qué hacen falta si ya existe {@code ListingSecurityTest}</h2>
 *
 * {@code @TestSecurity} construye la {@code SecurityIdentity} directamente y <strong>se salta
 * toda la cadena de validación</strong>. Eso lo hace rapidísimo y perfecto para comprobar reglas
 * de autorización, pero significa que estos fallos pasarían inadvertidos:
 *
 * <ul>
 *   <li>los roles configurados en un claim que Quarkus no lee (el clásico {@code groups} frente
 *       a {@code realm_access.roles}: sin {@code role-claim-path}, {@code @RolesAllowed} rechaza
 *       a todo el mundo aunque el token traiga los roles);</li>
 *   <li>una firma que no se verifica de verdad;</li>
 *   <li>un emisor o una URL de JWKS mal configurados.</li>
 * </ul>
 *
 * Con solo {@code @TestSecurity}, la suite entera seguiría verde y la aplicación rechazaría a
 * todos sus usuarios en producción. De ahí que unos pocos tests paguen el precio del contenedor.
 *
 * <p>Son cuatro a propósito: cada uno cuesta segundos, y su valor está en cubrir el cableado, no
 * en repetir reglas de negocio que ya se comprueban en milisegundos sin Quarkus.
 */
@QuarkusTest
/*
 * Sin @QuarkusTestResource: levantaría un Keycloak propio que chocaría con el que ya arrancó
 * Dev Services, y además vendría con sus usuarios de ejemplo en vez de los del marketplace.
 * KeycloakTestClient se autoconfigura leyendo las propiedades que Dev Services publicó.
 */
@DisplayName("Seguridad con tokens reales")
class RealTokenSecurityIT {

    /** Pide tokens al Keycloak que levantó Dev Services, igual que haría un cliente real. */
    private final KeycloakTestClient keycloak = new KeycloakTestClient();

    private static final String PRODUCTO = """
            { "title": "Teclado", "amount": "25.00", "currency": "EUR", "stock": 1 }
            """;

    @Test
    @DisplayName("un token real de vendedora permite publicar")
    void realSellerTokenWorks() {
        // Este test es el que de verdad prueba el cableado: firma verificada contra el JWKS,
        // emisor correcto, y el rol `seller` leído del claim donde Keycloak lo pone.
        given()
                .auth().oauth2(keycloak.getAccessToken("vendedora"))
                .contentType(ContentType.JSON).body(PRODUCTO)
                .when().post("/listings/products")
                .then().statusCode(201);
    }

    @Test
    @DisplayName("un token real de comprador no basta: el rol se lee del claim correcto")
    void realBuyerTokenIsRejected() {
        // Si el claim de roles estuviera mal configurado, el usuario llegaría SIN roles y esto
        // seguiría dando 403 — pero el test anterior fallaría. Los dos juntos fijan el mapeo.
        given()
                .auth().oauth2(keycloak.getAccessToken("comprador"))
                .contentType(ContentType.JSON).body(PRODUCTO)
                .when().post("/listings/products")
                .then().statusCode(403);
    }

    @Test
    @DisplayName("un token con la firma manipulada se rechaza con 401")
    void tamperedSignatureIsRejected() {
        var valido = keycloak.getAccessToken("vendedora");

        // Se altera el último carácter de la firma dejando intacto el payload. El token sigue
        // teniendo la forma correcta y unos claims perfectamente legítimos: lo único que falla
        // es la criptografía. Es exactamente lo que intentaría alguien que se fabricase un token
        // con el rol que le apetezca.
        var manipulado = valido.substring(0, valido.length() - 1)
                + (valido.endsWith("A") ? "B" : "A");

        given()
                .auth().oauth2(manipulado)
                .contentType(ContentType.JSON).body(PRODUCTO)
                .when().post("/listings/products")
                .then().statusCode(401);
    }

    @Test
    @DisplayName("un token que no es un JWT se rechaza con 401")
    void garbageTokenIsRejected() {
        given()
                .auth().oauth2("esto-no-es-un-token")
                .contentType(ContentType.JSON).body(PRODUCTO)
                .when().post("/listings/products")
                .then().statusCode(401);
    }
}
