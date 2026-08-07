package com.marketplace.catalog.infrastructure.rest;

/**
 * Cuerpo para crear un producto físico.
 *
 * <p>Deliberadamente <strong>no</strong> es el mismo record que la respuesta. Un DTO de entrada
 * y uno de salida tienen campos distintos por naturaleza: aquí no hay {@code id} (lo genera el
 * servidor) ni {@code status} (siempre nace en borrador) ni {@code sellerId} (saldrá del token
 * de autenticación en el módulo 5, jamás del cuerpo — si el cliente pudiera elegir el vendedor,
 * cualquiera publicaría en nombre de otro).
 *
 * <p>Todavía sin validación: en el paso 2.5 veremos qué entra por aquí sin ella, y entonces
 * añadiremos Bean Validation.
 */
public record CreateProductRequest(
        String title,
        String amount,
        String currency,
        int stock
) {
}
