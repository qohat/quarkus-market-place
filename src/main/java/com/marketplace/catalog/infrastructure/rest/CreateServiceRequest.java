package com.marketplace.catalog.infrastructure.rest;

/**
 * Cuerpo para crear un servicio reservable.
 *
 * <p>{@code timeZone} es un identificador de zona IANA ({@code "Europe/Madrid"}), no un desfase
 * fijo como {@code "+02:00"}. La diferencia importa: el desfase se queda obsoleto en cuanto
 * cambia el horario de verano, mientras que la zona conserva la intención ("los martes a las
 * 18:00, hora de Madrid") a lo largo de todo el año.
 */
public record CreateServiceRequest(
        String title,
        String amount,
        String currency,
        long slotMinutes,
        String timeZone
) {
}
