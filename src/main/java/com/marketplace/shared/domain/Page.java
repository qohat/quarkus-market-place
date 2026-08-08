package com.marketplace.shared.domain;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Una página de resultados junto con la información para navegar el resto.
 *
 * @param items      los elementos de esta página
 * @param page       índice de esta página, empezando en 0
 * @param size       tamaño de página solicitado
 * @param totalItems total de elementos que cumplen el criterio, no solo los de esta página
 */
public record Page<T>(List<T> items, int page, int size, long totalItems) {

    public Page {
        Objects.requireNonNull(items, "items must not be null");
        items = List.copyOf(items);
    }

    public static <T> Page<T> empty(PageRequest request) {
        return new Page<>(List.of(), request.page(), request.size(), 0);
    }

    public int totalPages() {
        if (totalItems == 0) {
            return 0;
        }
        // Ceil de la división entera, sin pasar por double.
        return (int) ((totalItems + size - 1) / size);
    }

    public boolean hasNext() {
        return (long) (page + 1) * size < totalItems;
    }

    public boolean hasPrevious() {
        return page > 0;
    }

    /**
     * Transforma los elementos conservando los metadatos de paginación.
     *
     * <p>Es lo que permite al adaptador REST convertir {@code Page<Listing>} en
     * {@code Page<ListingResponse>} sin recalcular totales ni arriesgarse a descuadrarlos.
     */
    public <R> Page<R> map(Function<? super T, ? extends R> mapper) {
        List<R> mapped = items.stream().<R>map(mapper).toList();
        return new Page<>(mapped, page, size, totalItems);
    }
}
