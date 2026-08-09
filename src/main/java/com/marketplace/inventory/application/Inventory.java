package com.marketplace.inventory.application;

import com.marketplace.catalog.domain.ListingId;
import com.marketplace.inventory.domain.BuyerId;
import com.marketplace.inventory.domain.Reservation;
import com.marketplace.inventory.domain.ReservationId;
import com.marketplace.inventory.domain.ReservationRepository;
import com.marketplace.inventory.domain.StockItem;
import com.marketplace.inventory.domain.StockRepository;
import com.marketplace.inventory.domain.event.StockChanged;
import com.marketplace.shared.outbox.Outbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Casos de uso de Inventario.
 *
 * <h2>La invariante que sostiene todo esto</h2>
 *
 * El contador de {@code stock_item} y las filas de {@code stock_reservation} tienen que decir lo
 * mismo: la suma de unidades en estado HELD debe coincidir con {@code reserved}. Por eso cada
 * operación toca las dos cosas <strong>en la misma transacción</strong>. Si se separaran, un
 * fallo a medias dejaría inventario apartado sin dueño, o dueños sin inventario.
 *
 * <p>Es también el motivo por el que este contexto sigue siendo un módulo dentro del mismo
 * despliegue: mientras las dos tablas vivan en la misma base de datos, esa consistencia es
 * gratis. El día que Inventario se extraiga a un servicio propio habrá que pagarla con el patrón
 * outbox y compensaciones, que es el módulo 7.
 *
 * <p>Nótese que no aparece ni {@code EntityManager} ni ninguna entidad JPA: esta capa solo conoce
 * los dos puertos. La primera versión sí los usaba, y el compilador lo impidió — ver
 * {@link ReservationRepository}.
 */
@ApplicationScoped
@Transactional
public class Inventory {

    /**
     * Tamaño del lote del barrido. Si se acumulan cien mil reservas vencidas, procesarlas de una
     * vez daría una transacción enorme que bloquea filas y castiga al resto del sistema. Mejor
     * muchas tandas pequeñas: la siguiente ejecución sigue por donde se quedó.
     */
    private static final int TAMANO_LOTE = 500;

    private final StockRepository stock;
    private final ReservationRepository reservations;
    private final Outbox outbox;

    /**
     * Cuánto vive una reserva sin pagar.
     *
     * <p>Es configurable porque es una decisión de negocio: corto libera antes el inventario pero
     * echa a quien esté tecleando la tarjeta; largo lo deja bloqueado más tiempo. Quince minutos
     * es lo habitual en comercio electrónico.
     */
    @ConfigProperty(name = "marketplace.inventory.reservation-ttl", defaultValue = "PT15M")
    Duration reservationTtl;

    Inventory(StockRepository stock, ReservationRepository reservations, Outbox outbox) {
        this.stock = stock;
        this.reservations = reservations;
        this.outbox = outbox;
    }

    /** Da de alta las existencias iniciales de un producto. */
    public void track(ListingId listingId, int units) {
        var item = StockItem.of(listingId, units);
        stock.create(item);
        outbox.publish(StockChanged.from(item));
    }

    public Optional<StockItem> stockOf(ListingId listingId) {
        return stock.find(listingId);
    }

    public Optional<Reservation> reservationOf(ReservationId id) {
        return reservations.find(id);
    }

    /**
     * Aparta unidades para un comprador.
     *
     * <p>El orden importa: primero se descuenta del contador y solo después se anota la reserva.
     * Si no hay unidades, {@code reserve} lanza y la transacción se deshace entera, así que no
     * queda rastro. Al revés —anotar y luego descontar— existiría un instante con una reserva que
     * nadie ha descontado.
     */
    public Reservation reserve(ListingId listingId, BuyerId buyerId, int units) {
        var updated = stock.reserve(listingId, units);
        var reservation = Reservation.hold(listingId, buyerId, units, reservationTtl, Instant.now());
        reservations.save(reservation);
        // El evento se anota en la MISMA transacción que el cambio: o se guardan los dos, o
        // ninguno. Aquí no se habla con Kafka; de eso se encarga el relay, después.
        outbox.publish(StockChanged.from(updated));
        return reservation;
    }

    /** El pago se completó: las unidades salen definitivamente del almacén. */
    public Reservation confirm(ReservationId reservationId) {
        var reservation = load(reservationId).confirm();
        reservations.update(reservation);
        outbox.publish(StockChanged.from(stock.confirm(reservation.listingId(), reservation.units())));
        return reservation;
    }

    /** El comprador canceló: las unidades vuelven a estar disponibles. */
    public Reservation cancel(ReservationId reservationId) {
        var reservation = load(reservationId).release();
        reservations.update(reservation);
        outbox.publish(StockChanged.from(stock.release(reservation.listingId(), reservation.units())));
        return reservation;
    }

    /**
     * Devuelve al inventario las reservas vencidas.
     *
     * <h2>Por qué es seguro ejecutarlo desde varias instancias a la vez</h2>
     *
     * Con varias réplicas desplegadas, dos barridos pueden coger la misma reserva. La protección
     * no es un candado sino el propio modelo: {@link Reservation#release()} exige que el estado
     * sea HELD, así que el segundo intento encuentra RELEASED y lanza. Se ignora, y las unidades
     * no se devuelven dos veces.
     *
     * <p><strong>Idempotencia por diseño del estado</strong>, en lugar de por coordinación entre
     * instancias. Es el mismo principio que sostendrá el módulo 7 frente a mensajes duplicados: la
     * operación es segura de repetir porque el estado no deja repetirla.
     *
     * @return cuántas reservas se liberaron
     */
    public int releaseExpired(Instant now) {
        int liberadas = 0;
        for (var vencida : reservations.findExpired(now, TAMANO_LOTE)) {
            try {
                var released = vencida.release();
                reservations.update(released);
                outbox.publish(StockChanged.from(
                        stock.release(released.listingId(), released.units())));
                liberadas++;
            } catch (IllegalStateException yaLiberada) {
                // Otra instancia se adelantó. No es un error: es el resultado esperado.
            }
        }
        return liberadas;
    }

    private Reservation load(ReservationId id) {
        return reservations.find(id)
                .orElseThrow(() -> new ReservationNotFoundException(id));
    }
}
