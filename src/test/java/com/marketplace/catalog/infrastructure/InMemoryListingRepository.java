package com.marketplace.catalog.infrastructure;

import com.marketplace.catalog.domain.Listing;
import com.marketplace.catalog.domain.ListingId;
import com.marketplace.catalog.domain.ListingRepository;
import com.marketplace.shared.domain.Page;
import com.marketplace.shared.domain.PageRequest;
import com.marketplace.shared.domain.SellerId;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementación en memoria del puerto, <strong>para tests</strong>.
 *
 * <p>Vivió en {@code src/main} durante el módulo 2 como adaptador real. Ahora que existe el
 * adaptador de Panache, se muda aquí por dos razones:
 *
 * <ul>
 *   <li><strong>Ya no debe empaquetarse.</strong> Es código que producción nunca ejecutaría.</li>
 *   <li><strong>Desaparece la ambigüedad de ARC.</strong> Dos beans implementando
 *       {@code ListingRepository} harían fallar el build con {@code Ambiguous dependencies}.
 *       Al dejar de ser un bean CDI —ya no lleva {@code @ApplicationScoped}— el conflicto se
 *       evapita sin necesidad de cualificadores ni perfiles.</li>
 * </ul>
 *
 * <p>Sigue siendo una clase normal, así que {@code new InMemoryListingRepository()} funciona
 * igual que antes. Eso es lo que mantiene {@code ListingCatalogTest} corriendo en milisegundos,
 * sin Docker y sin contenedor CDI: no es un mock que verifica interacciones, es una
 * implementación real y completa del puerto.
 *
 * <p>Nota que conserva el {@link ConcurrentHashMap}. Ya no lo comparten hilos de petición, pero
 * el coste es nulo y evita sorpresas si algún test ejercita concurrencia.
 */
public class InMemoryListingRepository implements ListingRepository {

    private final Map<ListingId, Listing> store = new ConcurrentHashMap<>();

    @Override
    public void save(Listing listing) {
        Objects.requireNonNull(listing, "listing must not be null");
        store.put(listing.id(), listing);
    }

    @Override
    public Optional<Listing> findById(ListingId id) {
        Objects.requireNonNull(id, "id must not be null");
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Page<Listing> findBySeller(SellerId sellerId, PageRequest pageRequest) {
        Objects.requireNonNull(sellerId, "sellerId must not be null");
        return paginate(
                listing -> listing.sellerId().equals(sellerId), pageRequest);
    }

    @Override
    public Page<Listing> findVisible(PageRequest pageRequest) {
        return paginate(Listing::isVisibleToBuyers, pageRequest);
    }

    /**
     * Reproduce la semántica del adaptador real: mismo orden total (título, luego id) y los
     * mismos metadatos de página.
     *
     * <p>Que el doble de test replique el orden importa: si aquí ordenara distinto que
     * PostgreSQL, los tests unitarios pasarían y la aplicación real devolvería otra cosa.
     */
    private Page<Listing> paginate(Predicate<Listing> criterio, PageRequest pageRequest) {
        Objects.requireNonNull(pageRequest, "pageRequest must not be null");

        List<Listing> coincidencias = store.values().stream()
                .filter(criterio)
                .sorted(Comparator.comparing(Listing::title)
                        .thenComparing(listing -> listing.id().value()))
                .toList();

        List<Listing> pagina = coincidencias.stream()
                .skip(pageRequest.offset())
                .limit(pageRequest.size())
                .toList();

        return new Page<>(pagina, pageRequest.page(), pageRequest.size(), coincidencias.size());
    }

    @Override
    public boolean deleteById(ListingId id) {
        Objects.requireNonNull(id, "id must not be null");
        return store.remove(id) != null;
    }

    @Override
    public long count() {
        return store.size();
    }

    /** Solo para tests: deja el repositorio vacío entre casos. */
    public void clear() {
        store.clear();
    }
}
