package com.marketplace.shared.infrastructure.rest;

import com.marketplace.shared.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Envoltura de una página en el JSON de la API.
 *
 * <h2>Por qué un objeto y no un array desnudo</h2>
 *
 * {@code GET /listings} podría devolver simplemente {@code [ ... ]}, y de hecho eso hacía hasta
 * ahora. El problema es que un array no deja sitio para los metadatos: el cliente no sabe si hay
 * más páginas, cuántos elementos hay en total ni en qué página está.
 *
 * <p>Las alternativas habituales son meter esa información en cabeceras (al estilo
 * {@code Link} del RFC 8288, que usa GitHub) o en el propio cuerpo. Las cabeceras son más
 * «RESTful»; el cuerpo es infinitamente más fácil de consumir desde cualquier cliente, y es lo
 * que hace la mayoría de las APIs modernas.
 *
 * <p>Y hay un motivo de compatibilidad que suele olvidarse: pasar de array a objeto es un cambio
 * incompatible. Empezar ya con un objeto deja sitio para añadir campos —facetas, tiempo de
 * consulta, cursores— sin romper a nadie.
 *
 * @param items       elementos de esta página
 * @param page        índice de página, empezando en 0
 * @param size        tamaño de página solicitado
 * @param totalItems  total de elementos que cumplen el criterio
 * @param totalPages  total de páginas disponibles
 * @param hasNext     si existe una página siguiente
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        boolean hasNext
) {

    public static <D, R> PageResponse<R> from(Page<D> page, Function<? super D, ? extends R> mapper) {
        Page<R> mapped = page.map(mapper);
        return new PageResponse<>(
                mapped.items(),
                mapped.page(),
                mapped.size(),
                mapped.totalItems(),
                mapped.totalPages(),
                mapped.hasNext());
    }
}
