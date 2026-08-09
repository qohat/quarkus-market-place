package com.marketplace.inventory.domain;

import com.marketplace.catalog.domain.ListingId;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Unas unidades apartadas para un comprador, con fecha de caducidad.
 *
 * <p>{@link StockItem} dice <em>cuántas</em> unidades están apartadas; esto dice <em>quién</em>
 * las tiene y <em>hasta cuándo</em>. Sin esa segunda parte el contador solo puede crecer: nadie
 * sabría qué reservas devolver cuando el comprador desaparece.
 *
 * @param expiresAt cuándo deja de valer. Un plazo corto libera antes el inventario pero echa a
 *                  quien esté tecleando su tarjeta; uno largo lo deja bloqueado. Es una decisión
 *                  de negocio, no técnica, y por eso entra como parámetro.
 */
public record Reservation(
        ReservationId id,
        ListingId listingId,
        BuyerId buyerId,
        int units,
        ReservationStatus status,
        Instant expiresAt) {

    public Reservation {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(listingId, "listingId must not be null");
        Objects.requireNonNull(buyerId, "buyerId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (units <= 0) {
            throw new IllegalArgumentException("units must be greater than zero: " + units);
        }
    }

    public static Reservation hold(
            ListingId listingId, BuyerId buyerId, int units, Duration ttl, Instant now) {
        return new Reservation(
                new ReservationId(UUID.randomUUID()),
                listingId, buyerId, units,
                ReservationStatus.HELD,
                now.plus(ttl));
    }

    /**
     * Si ya no vale.
     *
     * <p>El instante llega como parámetro en vez de leerse de {@code Instant.now()}: así el paso
     * del tiempo es un dato de entrada y la caducidad se puede probar sin esperar de verdad.
     */
    public boolean hasExpired(Instant now) {
        return status == ReservationStatus.HELD && !now.isBefore(expiresAt);
    }

    public Reservation confirm() {
        requireHeld("confirm");
        return new Reservation(id, listingId, buyerId, units, ReservationStatus.CONFIRMED, expiresAt);
    }

    public Reservation release() {
        requireHeld("release");
        return new Reservation(id, listingId, buyerId, units, ReservationStatus.RELEASED, expiresAt);
    }

    /**
     * Solo se puede salir de HELD, y solo una vez.
     *
     * <p>Es lo que hace <strong>idempotente</strong> el barrido de caducadas: si dos instancias de
     * la aplicación intentan liberar la misma reserva a la vez, la segunda encuentra un estado que
     * ya no es HELD y no devuelve las unidades por segunda vez. Sin esta comprobación, un
     * despliegue con varias réplicas inflaría el inventario poco a poco.
     */
    private void requireHeld(String operation) {
        if (status != ReservationStatus.HELD) {
            throw new IllegalStateException(
                    "cannot " + operation + " a reservation in status " + status);
        }
    }
}
