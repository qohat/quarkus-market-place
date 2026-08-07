package com.marketplace.catalog.infrastructure.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.catalog.domain.ListingStatus;
import com.marketplace.catalog.domain.ProductListing;
import com.marketplace.catalog.domain.ServiceListing;
import com.marketplace.shared.domain.Money;
import com.marketplace.shared.domain.SellerId;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica la <strong>forma exacta del JSON</strong> que verán los clientes de la API.
 *
 * <p>Es {@code @QuarkusTest} a propósito: inyecta el {@link ObjectMapper} que produce Quarkus,
 * no uno construido a mano. Así el test cubre también la configuración real (módulos
 * registrados, tratamiento de records, ajustes de la extensión), que es justo donde suelen
 * aparecer las sorpresas.
 *
 * <p>Un contrato de API es tan parte del sistema como el dominio: si cambia sin querer, rompes
 * a todos tus consumidores. Estos tests lo fijan.
 */
@QuarkusTest
@DisplayName("Contrato JSON de ListingResponse")
class ListingResponseTest {

    /** Quarkus expone su ObjectMapper como bean CDI; se puede personalizar con ObjectMapperCustomizer. */
    @Inject
    ObjectMapper objectMapper;

    private static final SellerId SELLER = SellerId.newId();
    private static final Money PRICE = Money.of("25.00", "EUR");

    private JsonNode serialize(Object dto) throws Exception {
        return objectMapper.readTree(objectMapper.writeValueAsString(dto));
    }

    @Nested
    @DisplayName("producto")
    class Product {

        @Test
        @DisplayName("usa el discriminador PRODUCT y expone el stock como availableUnits")
        void serialisesProduct() throws Exception {
            var product = ProductListing
                    .draft(SELLER, "Teclado mecánico", PRICE, 40)
                    .withStatus(ListingStatus.PUBLISHED);

            var json = serialize(ListingResponse.from(product));

            assertEquals("PRODUCT", json.get("type").asText());
            assertEquals("Teclado mecánico", json.get("title").asText());
            assertEquals("PUBLISHED", json.get("status").asText());
            assertEquals(40, json.get("availableUnits").asInt());
            assertEquals(product.id().toString(), json.get("id").asText());
        }

        @Test
        @DisplayName("omite el bloque service en lugar de emitirlo a null")
        void omitsServiceBlock() throws Exception {
            var product = ProductListing.draft(SELLER, "Teclado", PRICE, 40);

            var json = serialize(ListingResponse.from(product));

            // @JsonInclude(NON_NULL): la clave no aparece siquiera.
            assertFalse(json.has("service"), "un producto no debe llevar bloque 'service'");
        }
    }

    @Nested
    @DisplayName("servicio")
    class Service {

        @Test
        @DisplayName("usa el discriminador SERVICE e incluye el bloque service")
        void serialisesService() throws Exception {
            var service = ServiceListing.draft(
                    SELLER, "Clase de guitarra", PRICE,
                    Duration.ofMinutes(90), ZoneId.of("Europe/Madrid"));

            var json = serialize(ListingResponse.from(service));

            assertEquals("SERVICE", json.get("type").asText());
            assertTrue(json.has("service"));
            assertEquals(90, json.get("service").get("slotMinutes").asLong());
            assertEquals("Europe/Madrid", json.get("service").get("timeZone").asText());
            assertEquals(1, json.get("availableUnits").asInt());
        }
    }

    @Nested
    @DisplayName("dinero")
    class MoneySerialisation {

        @Test
        @DisplayName("el importe viaja como string para no perder precisión en el cliente")
        void amountIsAString() throws Exception {
            var product = ProductListing.draft(SELLER, "Teclado", PRICE, 1);

            var price = serialize(ListingResponse.from(product)).get("price");

            assertTrue(price.get("amount").isTextual(),
                    "amount debe ser string: como número, JSON.parse lo convertiría a double");
            assertEquals("25.00", price.get("amount").asText());
            assertEquals("EUR", price.get("currency").asText());
        }

        @Test
        @DisplayName("conserva la escala de la moneda")
        void keepsCurrencyScale() {
            // Como número JSON, 25.00 se leería como 25 en JavaScript. Como string, no.
            assertEquals("25.00", MoneyView.from(Money.of("25", "EUR")).amount());
            assertEquals("1200", MoneyView.from(Money.of("1200", "JPY")).amount());
        }

        @Test
        @DisplayName("el viaje de ida y vuelta preserva el importe")
        void roundTripsThroughTheDomain() {
            var original = Money.of("1234.56", "EUR");

            assertEquals(original, MoneyView.from(original).toDomain());
        }
    }

    @Nested
    @DisplayName("peticiones de entrada")
    class Requests {

        @Test
        @DisplayName("deserializa la creación de un producto")
        void deserialisesCreateProduct() throws Exception {
            String body = """
                    {
                      "title": "Teclado mecánico",
                      "amount": "25.00",
                      "currency": "EUR",
                      "stock": 40
                    }
                    """;

            var request = objectMapper.readValue(body, CreateProductRequest.class);

            assertEquals("Teclado mecánico", request.title());
            assertEquals("25.00", request.amount());
            assertEquals(40, request.stock());
        }

        @Test
        @DisplayName("deserializa la creación de un servicio")
        void deserialisesCreateService() throws Exception {
            String body = """
                    {
                      "title": "Clase de guitarra",
                      "amount": "30.00",
                      "currency": "EUR",
                      "slotMinutes": 60,
                      "timeZone": "Europe/Madrid"
                    }
                    """;

            var request = objectMapper.readValue(body, CreateServiceRequest.class);

            assertEquals(60, request.slotMinutes());
            assertEquals("Europe/Madrid", request.timeZone());
        }
    }
}
