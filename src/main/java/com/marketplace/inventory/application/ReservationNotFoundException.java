package com.marketplace.inventory.application;

import com.marketplace.inventory.domain.ReservationId;

/** No existe ninguna reserva con ese identificador. */
public class ReservationNotFoundException extends RuntimeException {

    private final ReservationId reservationId;

    public ReservationNotFoundException(ReservationId reservationId) {
        super("No reservation exists with id " + reservationId);
        this.reservationId = reservationId;
    }

    public ReservationId reservationId() {
        return reservationId;
    }
}
