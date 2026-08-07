package com.marketplace.catalog.domain;

import com.marketplace.shared.domain.Money;
import com.marketplace.shared.domain.SellerId;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Publicación de un servicio reservable: se reservan <em>franjas horarias</em>, no unidades.
 *
 * <p>Su recurso escaso es el tiempo, y eso cambia todo el modelo de concurrencia respecto a
 * {@link ProductListing}: dos reservas chocan si se solapan en el calendario, no si agotan un
 * contador. Ahí aparecerán rangos, husos horarios y bloqueo pesimista.
 *
 * <p>{@code timeZone} se guarda explícitamente porque la disponibilidad de un profesional se
 * define en <em>su</em> zona horaria ("los martes de 9 a 14"), no en UTC. Guardar solo un
 * instante UTC pierde esa intención en cuanto cambia el horario de verano.
 */
public record ServiceListing(
        ListingId id,
        SellerId sellerId,
        String title,
        Money price,
        ListingStatus status,
        Duration slotDuration,
        ZoneId timeZone,
        int maxConcurrentBookings
) implements Listing {

    public ServiceListing {
        Objects.requireNonNull(id, "id no puede ser null");
        Objects.requireNonNull(sellerId, "sellerId no puede ser null");
        Objects.requireNonNull(title, "title no puede ser null");
        Objects.requireNonNull(price, "price no puede ser null");
        Objects.requireNonNull(status, "status no puede ser null");
        Objects.requireNonNull(slotDuration, "slotDuration no puede ser null");
        Objects.requireNonNull(timeZone, "timeZone no puede ser null");

        if (title.isBlank()) {
            throw new IllegalArgumentException("El título no puede estar vacío");
        }
        if (!price.isPositive()) {
            throw new IllegalArgumentException("El precio debe ser positivo, pero era " + price);
        }
        if (slotDuration.isZero() || slotDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "La duración de la franja debe ser positiva: " + slotDuration);
        }
        if (maxConcurrentBookings < 1) {
            throw new IllegalArgumentException(
                    "Debe admitir al menos una reserva simultánea: " + maxConcurrentBookings);
        }
    }

    /** Crea un servicio nuevo en borrador, con una sola plaza por franja. */
    public static ServiceListing draft(
            SellerId sellerId, String title, Money price, Duration slotDuration, ZoneId timeZone) {
        return new ServiceListing(
                ListingId.newId(), sellerId, title, price, ListingStatus.DRAFT,
                slotDuration, timeZone, 1);
    }

    @Override
    public int availableUnits() {
        return maxConcurrentBookings;
    }

    @Override
    public ServiceListing withStatus(ListingStatus newStatus) {
        Objects.requireNonNull(newStatus, "newStatus no puede ser null");
        if (status.isTerminal() && newStatus != status) {
            throw new IllegalStateException(
                    "No se puede salir del estado terminal " + status);
        }
        return new ServiceListing(
                id, sellerId, title, price, newStatus, slotDuration, timeZone, maxConcurrentBookings);
    }
}
