package com.marketplace.catalog.infrastructure.persistence;

import com.marketplace.catalog.domain.Listing;
import com.marketplace.catalog.domain.ListingId;
import com.marketplace.catalog.domain.ServiceListing;
import com.marketplace.shared.domain.SellerId;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.time.Duration;
import java.time.ZoneId;

/** Fila de un servicio reservable: {@code listing_type = 'SERVICE'}. */
@Entity
@DiscriminatorValue(ServiceListingEntity.TYPE)
public class ServiceListingEntity extends ListingEntity {

    static final String TYPE = "SERVICE";

    /*
     * La duración se guarda como minutos enteros, no como un Duration serializado ni como un
     * INTERVAL de PostgreSQL.
     *
     * Un INTERVAL admite cosas como "1 mes", que no tiene una duración fija en minutos y
     * complicaría cualquier aritmética de calendario. Un entero de minutos es inequívoco,
     * indexable y comparable en SQL sin funciones especiales.
     */
    @Column(name = "slot_minutes")
    Integer slotMinutes;

    /*
     * La zona se guarda como identificador IANA ("Europe/Madrid"), no como desfase.
     *
     * Un desfase fijo ("+02:00") queda obsoleto en cuanto cambia el horario de verano, y con él
     * todas las disponibilidades futuras del vendedor. El identificador conserva la intención
     * ("los martes a las 18:00, hora de Madrid") de forma indefinida.
     */
    @Column(name = "time_zone", length = 64)
    String timeZone;

    @Column(name = "max_bookings")
    Integer maxBookings;

    protected ServiceListingEntity() {
    }

    ServiceListingEntity(ServiceListing listing) {
        super(listing);
        this.slotMinutes = (int) listing.slotDuration().toMinutes();
        this.timeZone = listing.timeZone().getId();
        this.maxBookings = listing.maxConcurrentBookings();
    }

    @Override
    public ServiceListing toDomain() {
        return new ServiceListing(
                new ListingId(id),
                new SellerId(sellerId),
                title,
                money(),
                status,
                Duration.ofMinutes(slotMinutes),
                ZoneId.of(timeZone),
                maxBookings);
    }

    @Override
    public void updateFrom(Listing listing) {
        if (!(listing instanceof ServiceListing service)) {
            throw new IllegalStateException(
                    "Cannot update a service listing from " + listing.getClass().getSimpleName());
        }
        updateCommonFrom(service);
        this.slotMinutes = (int) service.slotDuration().toMinutes();
        this.timeZone = service.timeZone().getId();
        this.maxBookings = service.maxConcurrentBookings();
    }
}
