package com.marketplace.catalog.infrastructure.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo para crear un producto físico.
 *
 * <p>Deliberadamente <strong>no</strong> es el mismo record que la respuesta. Aquí no hay
 * {@code id} (lo genera el servidor), ni {@code status} (siempre nace en borrador), ni
 * {@code sellerId} (saldrá del token en el módulo 5, jamás del cuerpo — si el cliente pudiera
 * elegir el vendedor, cualquiera publicaría en nombre de otro).
 *
 * <p><strong>Qué se valida aquí y qué no.</strong> Estas anotaciones comprueban la <em>forma</em>
 * del payload: que los campos estén presentes y tengan una sintaxis plausible. Las reglas de
 * negocio —que el precio sea estrictamente positivo, que la moneda exista de verdad— se quedan
 * en {@code Money} y {@code ProductListing}. Duplicarlas aquí las condenaría a divergir: alguien
 * cambiaría una y olvidaría la otra, y además la validación se saltaría por completo cuando la
 * misma operación llegue por Kafka en el módulo 7.
 *
 * <p>Los mensajes son explícitos en inglés y no los que Hibernate Validator genera por defecto.
 * Razón práctica: los mensajes por defecto se traducen según el locale del servidor, de modo que
 * la respuesta de tu API cambiaría según dónde esté desplegada — y tus tests fallarían al
 * cambiar de máquina.
 */
public record CreateProductRequest(

        @NotBlank(message = "title is required")
        @Size(max = 200, message = "title must be at most 200 characters")
        String title,

        /*
         * El importe llega como String por la misma razón por la que sale como String: para
         * que ningún parser intermedio lo convierta en un double y le cambie el valor.
         * El patrón acepta un decimal; que la escala encaje con la moneda lo decide Money.
         */
        @NotBlank(message = "amount is required")
        @Pattern(
                regexp = "-?\\d+(\\.\\d+)?",
                message = "amount must be a decimal number, e.g. \"25.00\"")
        String amount,

        /** Código ISO 4217. Que exista de verdad lo comprueba {@code Currency.getInstance}. */
        @NotBlank(message = "currency is required")
        @Pattern(
                regexp = "[A-Z]{3}",
                message = "currency must be a 3-letter ISO 4217 code, e.g. \"EUR\"")
        String currency,

        /*
         * Cero es válido: un producto puede publicarse agotado y reponerse después. Lo que no
         * tiene sentido es un stock negativo.
         */
        @PositiveOrZero(message = "stock cannot be negative")
        int stock
) {
}
