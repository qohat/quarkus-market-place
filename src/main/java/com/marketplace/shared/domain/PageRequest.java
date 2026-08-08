package com.marketplace.shared.domain;

/**
 * Petición de una página de resultados.
 *
 * <p>Vive en el dominio, no en la capa REST, porque paginar no es una necesidad de HTTP sino
 * del propio negocio: ningún caso de uso quiere "todas las publicaciones" cuando hay cien mil.
 *
 * <h2>Por qué existe el límite máximo de tamaño</h2>
 *
 * Sin él, {@code ?size=1000000} es un ataque de denegación de servicio de un solo carácter:
 * la base de datos materializa un millón de filas, Hibernate construye un millón de entidades,
 * Jackson serializa un millón de objetos, y el proceso se queda sin heap. Y no hace falta mala
 * fe — basta un cliente que "quiere cargarlo todo de una vez".
 *
 * <p>El límite es el primer control de admisión del sistema. En el módulo 9 se sumarán rate
 * limiting y timeouts, pero este es gratis y ataja el caso más burdo.
 */
public record PageRequest(int page, int size) {

    /** Tamaño usado cuando el cliente no pide nada en concreto. */
    public static final int DEFAULT_SIZE = 20;

    /** Techo absoluto. Ningún cliente puede superarlo, ni siquiera pidiéndolo. */
    public static final int MAX_SIZE = 100;

    public PageRequest {
        if (page < 0) {
            throw new IllegalArgumentException("Page index cannot be negative: " + page);
        }
        if (size < 1) {
            throw new IllegalArgumentException("Page size must be at least 1, but was " + size);
        }
        if (size > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "Page size cannot exceed %d, but was %d".formatted(MAX_SIZE, size));
        }
    }

    public static PageRequest of(int page, int size) {
        return new PageRequest(page, size);
    }

    public static PageRequest first() {
        return new PageRequest(0, DEFAULT_SIZE);
    }

    /**
     * Cuántas filas se descartan antes de empezar a devolver.
     *
     * <p>Este {@code offset} es cómodo y es el que usaremos, pero conviene saber que
     * <strong>degrada linealmente</strong>: para servir la página 5.000 con tamaño 20, PostgreSQL
     * tiene que recorrer y descartar 100.000 filas antes de devolver 20. Es rápido en las
     * primeras páginas e insostenible en las profundas.
     *
     * <p>La alternativa a escala es la <em>paginación por keyset</em> (o «seek»): en vez de
     * "sáltate 100.000", se pide "dame los 20 siguientes a este valor concreto", lo que aprovecha
     * el índice y cuesta lo mismo en la página 1 que en la 5.000. El precio es perder el salto
     * directo a una página arbitraria — por eso la usan los feeds con scroll infinito y no las
     * tablas con numeritos de página.
     */
    public int offset() {
        return page * size;
    }
}
