package com.marketplace.inventory.infrastructure.persistence;

import com.marketplace.catalog.domain.ListingId;
import com.marketplace.inventory.domain.BuyerId;
import com.marketplace.inventory.domain.Reservation;
import com.marketplace.inventory.domain.ReservationId;
import com.marketplace.inventory.domain.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Fila de {@code stock_reservation}. */
@Entity
@Table(name = "stock_reservation")
public class ReservationEntity {

    @Id
    UUID id;

    @Column(name = "listing_id", nullable = false)
    UUID listingId;

    @Column(name = "buyer_id", nullable = false)
    UUID buyerId;

    @Column(nullable = false)
    int units;

    /** STRING, nunca ORDINAL: reordenar el enum no debe cambiar lo ya escrito (módulo 3). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected ReservationEntity() {
    }

    static ReservationEntity fromDomain(Reservation reservation) {
        var entity = new ReservationEntity();
        entity.id = reservation.id().value();
        entity.listingId = reservation.listingId().value();
        entity.buyerId = reservation.buyerId().value();
        entity.units = reservation.units();
        entity.status = reservation.status();
        entity.expiresAt = reservation.expiresAt();
        entity.createdAt = Instant.now();
        return entity;
    }

    Reservation toDomain() {
        return new Reservation(
                new ReservationId(id),
                new ListingId(listingId),
                new BuyerId(buyerId),
                units,
                status,
                expiresAt);
    }

    void updateFrom(Reservation reservation) {
        this.status = reservation.status();
    }
}
