package com.marketplace.catalog.infrastructure;

import com.marketplace.catalog.domain.Listing;
import com.marketplace.catalog.domain.ListingId;
import com.marketplace.catalog.domain.ListingRepository;
import com.marketplace.shared.domain.SellerId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adaptador de persistencia en memoria. Sustituido por Hibernate ORM en el módulo 3.
 *
 * <p><strong>{@code @ApplicationScoped} implica concurrencia.</strong> Hay <em>una sola
 * instancia</em> de esta clase para toda la aplicación, y todas las peticiones HTTP la usan a la
 * vez desde hilos distintos. Un {@code HashMap} normal aquí sería una bomba: al redimensionarse
 * bajo escritura concurrente puede corromperse o entrar en bucle infinito. De ahí
 * {@link ConcurrentHashMap}.
 *
 * <p>Esa es la regla general con beans de scope largo: <strong>o no tienen estado mutable, o su
 * estado es thread-safe</strong>. La mayoría de tus servicios caerán en el primer grupo.
 *
 * <p>Aviso para el módulo 6: que cada operación del mapa sea atómica <em>no</em> significa que
 * una secuencia de operaciones lo sea. Un "lee el stock, réstale 1, guárdalo" desde dos hilos
 * a la vez sobrevende. Ahí es donde aparecerán {@code compute} y las actualizaciones
 * condicionales.
 */
@ApplicationScoped
public class InMemoryListingRepository implements ListingRepository {

    private final Map<ListingId, Listing> store = new ConcurrentHashMap<>();

    @Override
    public void save(Listing listing) {
        Objects.requireNonNull(listing, "listing no puede ser null");
        store.put(listing.id(), listing);
    }

    @Override
    public Optional<Listing> findById(ListingId id) {
        Objects.requireNonNull(id, "id no puede ser null");
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Listing> findBySeller(SellerId sellerId) {
        Objects.requireNonNull(sellerId, "sellerId no puede ser null");
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
        Objects.requireNonNull(id, "id no puede ser null");
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
