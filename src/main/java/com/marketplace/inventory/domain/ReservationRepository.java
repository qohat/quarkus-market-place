package com.marketplace.inventory.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Puerto de salida para las reservas.
 *
 * <p>Nació de un error de compilación, y merece la pena contarlo: la primera versión de
 * {@code Inventory} usaba directamente el {@code EntityManager} y la entidad JPA. El compilador
 * lo impidió porque los métodos de la entidad son package-private, y esa negativa señalaba algo
 * más de fondo que un problema de visibilidad: la capa de aplicación estaba tocando
 * infraestructura.
 *
 * <p>Es el mismo criterio que en el módulo 3 con {@code ListingRepository}. Mantenerlo aquí no es
 * simetría por gusto: es lo que permitirá extraer Inventario a un servicio propio cambiando solo
 * el adaptador.
 */
public interface ReservationRepository {

    void save(Reservation reservation);

    Optional<Reservation> find(ReservationId id);

    /** Persiste un cambio de estado sobre una reserva ya existente. */
    void update(Reservation reservation);

    /**
     * Reservas vencidas y todavía retenidas, para el barrido.
     *
     * @param limit tamaño máximo del lote. Existe para que una acumulación de vencidas no
     *              produzca una transacción gigante que bloquee filas y castigue al resto del
     *              sistema; la siguiente ejecución continúa por donde se quedó.
     */
    List<Reservation> findExpired(Instant now, int limit);
}
