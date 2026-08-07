package com.marketplace.catalog.domain;

import com.marketplace.catalog.domain.FulfillmentCheck.Fulfillable;
import com.marketplace.catalog.domain.FulfillmentCheck.InsufficientAvailability;
import com.marketplace.catalog.domain.FulfillmentCheck.NotAcceptingOrders;
import com.marketplace.shared.domain.Money;
import com.marketplace.shared.domain.SellerId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Listings")
class ListingsTest {

    private static final SellerId SELLER = SellerId.newId();
    private static final Money PRICE = Money.of("25.00", "EUR");

    private static ProductListing publishedProduct(int stock) {
        return ProductListing.draft(SELLER, "Teclado mecánico", PRICE, stock)
                .withStatus(ListingStatus.PUBLISHED);
    }

    private static ServiceListing publishedService(int concurrentSlots) {
        var draft = ServiceListing.draft(
                SELLER, "Clase de guitarra", PRICE, Duration.ofMinutes(60), ZoneId.of("Europe/Madrid"));
        return new ServiceListing(
                draft.id(), draft.sellerId(), draft.title(), draft.price(),
                ListingStatus.PUBLISHED, draft.slotDuration(), draft.timeZone(), concurrentSlots);
    }

    @Nested
    @DisplayName("check() sobre productos")
    class Products {

        @Test
        @DisplayName("es servible cuando hay stock suficiente")
        void fulfillableWhenEnoughStock() {
            var result = Listings.check(publishedProduct(10), 3);

            // assertInstanceOf devuelve el valor ya casteado: pattern matching en el test.
            var fulfillable = assertInstanceOf(Fulfillable.class, result);
            assertEquals(Money.of("75.00", "EUR"), fulfillable.total());
            assertTrue(result.isFulfillable());
        }

        @Test
        @DisplayName("no es servible cuando falta stock, e informa de cuánto hay")
        void insufficientStockCarriesTheNumbers() {
            var result = Listings.check(publishedProduct(2), 5);

            var insufficient = assertInstanceOf(InsufficientAvailability.class, result);
            assertEquals(5, insufficient.requested());
            assertEquals(2, insufficient.available());
        }

        @Test
        @DisplayName("permite consumir exactamente todo el stock")
        void exactStockIsFulfillable() {
            assertTrue(Listings.check(publishedProduct(4), 4).isFulfillable());
        }
    }

    @Nested
    @DisplayName("check() sobre servicios")
    class Services {

        @Test
        @DisplayName("es servible dentro del límite de reservas simultáneas")
        void fulfillableWithinConcurrencyLimit() {
            assertTrue(Listings.check(publishedService(3), 2).isFulfillable());
        }

        @Test
        @DisplayName("no es servible por encima del límite")
        void notFulfillableAboveConcurrencyLimit() {
            assertInstanceOf(
                    InsufficientAvailability.class, Listings.check(publishedService(1), 2));
        }
    }

    @Nested
    @DisplayName("estado de la publicación")
    class Status {

        @Test
        @DisplayName("un borrador no admite pedidos aunque tenga stock")
        void draftDoesNotAcceptOrders() {
            var draft = ProductListing.draft(SELLER, "Teclado", PRICE, 100);

            var result = Listings.check(draft, 1);

            var rejected = assertInstanceOf(NotAcceptingOrders.class, result);
            assertEquals(ListingStatus.DRAFT, rejected.status());
        }

        @Test
        @DisplayName("una publicación pausada es visible pero no comprable")
        void pausedIsVisibleButNotOrderable() {
            var paused = publishedProduct(10).withStatus(ListingStatus.PAUSED);

            assertTrue(paused.isVisibleToBuyers());
            assertFalse(paused.acceptsOrders());
            assertInstanceOf(NotAcceptingOrders.class, Listings.check(paused, 1));
        }

        @Test
        @DisplayName("ARCHIVED es terminal: no se puede volver a publicar")
        void archivedIsTerminal() {
            var archived = publishedProduct(10).withStatus(ListingStatus.ARCHIVED);

            assertThrows(
                    IllegalStateException.class, () -> archived.withStatus(ListingStatus.PUBLISHED));
        }

        @Test
        @DisplayName("withStatus conserva el tipo concreto (retorno covariante)")
        void withStatusPreservesConcreteType() {
            // El tipo de la variable es ProductListing, no Listing: no hace falta cast.
            ProductListing published = ProductListing
                    .draft(SELLER, "Teclado", PRICE, 5)
                    .withStatus(ListingStatus.PUBLISHED);

            assertEquals(5, published.availableStock());
        }
    }

    @Nested
    @DisplayName("describe() resuelve el ADT con record patterns")
    class Describe {

        @Test
        void describesEachOutcome() {
            assertEquals(
                    "Disponible por un total de 50.00 EUR",
                    Listings.describe(Listings.check(publishedProduct(10), 2)));

            assertEquals(
                    "Solo quedan 1 unidades y se pidieron 4",
                    Listings.describe(Listings.check(publishedProduct(1), 4)));

            assertEquals(
                    "La publicación no admite pedidos (estado: DRAFT)",
                    Listings.describe(Listings.check(
                            ProductListing.draft(SELLER, "Teclado", PRICE, 1), 1)));
        }
    }

    @Nested
    @DisplayName("validaciones de construcción")
    class Validation {

        @Test
        @DisplayName("rechaza precio no positivo, título vacío y stock negativo")
        void rejectsInvalidProducts() {
            assertThrows(IllegalArgumentException.class,
                    () -> ProductListing.draft(SELLER, "  ", PRICE, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> ProductListing.draft(SELLER, "Teclado", Money.zero("EUR"), 1));
            assertThrows(IllegalArgumentException.class,
                    () -> ProductListing.draft(SELLER, "Teclado", PRICE, -1));
        }

        @Test
        @DisplayName("rechaza franjas de duración no positiva")
        void rejectsInvalidServices() {
            assertThrows(IllegalArgumentException.class,
                    () -> ServiceListing.draft(
                            SELLER, "Clase", PRICE, Duration.ZERO, ZoneId.of("Europe/Madrid")));
        }

        @Test
        @DisplayName("rechaza cantidades menores que 1")
        void rejectsNonPositiveQuantity() {
            assertThrows(IllegalArgumentException.class,
                    () -> Listings.check(publishedProduct(10), 0));
        }
    }
}
