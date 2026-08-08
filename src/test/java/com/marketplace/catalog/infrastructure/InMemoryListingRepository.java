package com.marketplace.catalog.infrastructure;

import com.marketplace.catalog.domain.Listing;
import com.marketplace.catalog.domain.ListingId;
import com.marketplace.catalog.domain.ListingRepository;
import com.marketplace.shared.domain.SellerId;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    public List<Listing> findBySeller(SellerId sellerId) {
        Objects.requireNonNull(sellerId, "sellerId must not be null");
        return store.values().stream()
                .filter(listing -> listing.sellerId().equals(sellerId))
                .sorted(Comparator.comparing(Listing::title))
                .toList();
    }

    @Override
    public List<Listing> findVisible() {
        return store.values().stream()
                .filter(Listing::isVisibleToBuyers)
                .sorted(Comparator.comparing(Listing::title))
                .toList();
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
