package com.marketplace.shared.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests del value object Money. Sin {@code @QuarkusTest}: es JUnit puro, sin contenedor CDI,
 * sin arrancar la aplicación. Corren en milisegundos.
 */
@DisplayName("Money")
class MoneyTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Nested
    @DisplayName("normalización de escala")
    class ScaleNormalisation {

        @Test
        @DisplayName("ajusta la escala a los decimales de la moneda")
        void normalisesScaleToCurrencyDigits() {
            assertEquals(2, Money.of("34.5", "EUR").amount().scale());
            assertEquals(2, Money.of("34", "EUR").amount().scale());
            // El yen no tiene decimales
            assertEquals(0, Money.of("1200", "JPY").amount().scale());
        }

        @Test
        @DisplayName("34.5 EUR y 34.50 EUR son el mismo Money")
        void equalityIgnoresInputScale() {
            // Este es el bug que la normalización evita: BigDecimal.equals compara la escala,
            // así que sin normalizar estos dos Money serían distintos pese a valer lo mismo.
            assertEquals(Money.of("34.5", "EUR"), Money.of("34.50", "EUR"));
            assertEquals(Money.of("34.5", "EUR").hashCode(), Money.of("34.50", "EUR").hashCode());

            // Demostración de que el problema es real a nivel de BigDecimal:
            assertNotEquals(new BigDecimal("34.5"), new BigDecimal("34.50"));
        }

        @Test
        @DisplayName("rechaza más decimales de los que admite la moneda")
        void rejectsExcessPrecision() {
            // RoundingMode.UNNECESSARY: preferimos fallar a redondear dinero en silencio.
            assertThrows(ArithmeticException.class, () -> Money.of("34.567", "EUR"));
        }
    }

    @Nested
    @DisplayName("aritmética")
    class Arithmetic {

        @Test
        @DisplayName("suma y resta importes de la misma moneda")
        void addsAndSubtracts() {
            var a = Money.of("10.50", "EUR");
            var b = Money.of("4.25", "EUR");

            assertEquals(Money.of("14.75", "EUR"), a.plus(b));
            assertEquals(Money.of("6.25", "EUR"), a.minus(b));
        }

        @Test
        @DisplayName("permite resultados negativos: la regla de negocio decide, no el tipo")
        void allowsNegativeResults() {
            var result = Money.of("5.00", "EUR").minus(Money.of("8.00", "EUR"));

            assertEquals(Money.of("-3.00", "EUR"), result);
            assertTrue(result.isNegative());
        }

        @Test
        @DisplayName("multiplica por una cantidad entera")
        void multipliesByQuantity() {
            assertEquals(Money.of("31.50", "EUR"), Money.of("10.50", "EUR").times(3));
            assertTrue(Money.of("10.50", "EUR").times(0).isZero());
        }

        @Test
        @DisplayName("rechaza multiplicar por un factor negativo")
        void rejectsNegativeFactor() {
            assertThrows(IllegalArgumentException.class, () -> Money.of("10.00", "EUR").times(-1));
        }
    }

    @Nested
    @DisplayName("seguridad de moneda")
    class CurrencySafety {

        @Test
        @DisplayName("no deja operar entre monedas distintas")
        void rejectsMixedCurrencies() {
            var euros = Money.of("10.00", "EUR");
            var dolares = Money.of("10.00", "USD");

            assertThrows(IllegalArgumentException.class, () -> euros.plus(dolares));
            assertThrows(IllegalArgumentException.class, () -> euros.minus(dolares));
            assertThrows(IllegalArgumentException.class, () -> euros.compareTo(dolares));
        }

        @Test
        @DisplayName("10 EUR no es igual a 10 USD")
        void sameAmountDifferentCurrencyIsNotEqual() {
            assertNotEquals(Money.of("10.00", "EUR"), Money.of("10.00", "USD"));
        }

        @Test
        @DisplayName("ordena importes de la misma moneda")
        void comparesSameCurrency() {
            assertTrue(Money.of("5.00", "EUR").compareTo(Money.of("10.00", "EUR")) < 0);
            assertEquals(0, Money.of("5.00", "EUR").compareTo(Money.of("5.000", "EUR")));
        }
    }

    @Test
    @DisplayName("rechaza componentes nulos")
    void rejectsNulls() {
        assertThrows(NullPointerException.class, () -> new Money(null, EUR));
        assertThrows(NullPointerException.class, () -> new Money(BigDecimal.ONE, null));
    }
}
