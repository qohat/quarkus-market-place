package com.marketplace.catalog.infrastructure.rest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo para crear un servicio reservable.
 *
 * <p>{@code timeZone} es un identificador de zona IANA ({@code "Europe/Madrid"}), no un desfase
 * fijo como {@code "+02:00"}. La diferencia importa: el desfase queda obsoleto en cuanto cambia
 * el horario de verano, mientras que la zona conserva la intención ("los martes a las 18:00,
 * hora de Madrid") durante todo el año.
 *
 * <p>La zona se valida solo por formato. Comprobar que existe en la base de datos IANA es cosa
 * de {@code ZoneId.of}, igual que la existencia de la moneda es cosa de {@code Currency}: no
 * tiene sentido replicar aquí un catálogo que la biblioteca estándar ya conoce.
 */
public record CreateServiceRequest(

        @NotBlank(message = "title is required")
        @Size(max = 200, message = "title must be at most 200 characters")
        String title,

        @NotBlank(message = "amount is required")
        @Pattern(
                regexp = "-?\\d+(\\.\\d+)?",
                message = "amount must be a decimal number, e.g. \"30.00\"")
        String amount,

        @NotBlank(message = "currency is required")
        @Pattern(
                regexp = "[A-Z]{3}",
                message = "currency must be a 3-letter ISO 4217 code, e.g. \"EUR\"")
        String currency,

        /*
         * Una franja de cero minutos no es una franja, y por encima de un día natural deja de
         * ser una reserva por franjas. El límite superior no es un capricho: sin él, un
         * slotMinutes de varios millones desbordaría los cálculos de calendario del módulo 6.
         */
        @Positive(message = "slotMinutes must be greater than zero")
        @Max(value = 1440, message = "slotMinutes cannot exceed 1440 (24 hours)")
        long slotMinutes,

        @NotBlank(message = "timeZone is required")
        @Pattern(
                regexp = "[A-Za-z]+/[A-Za-z_+\\-/]+|UTC",
                message = "timeZone must be an IANA zone id, e.g. \"Europe/Madrid\"")
        String timeZone
) {
}
