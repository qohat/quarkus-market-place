package com.marketplace.purchase.application;

import com.marketplace.catalog.domain.ListingId;
import com.marketplace.inventory.application.Inventory;
import com.marketplace.inventory.domain.BuyerId;
import com.marketplace.inventory.domain.Reservation;
import com.marketplace.purchase.domain.PaymentDeclinedException;
import com.marketplace.purchase.domain.PaymentGateway;
import com.marketplace.shared.domain.Money;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * La saga de compra, con orquestación.
 *
 * <pre>
 *   1. reservar stock  ──→  2. cobrar  ──→  3. confirmar reserva
 *          │                    │
 *          │               ✗ rechazado
 *          └──────── liberar reserva  ← compensación
 * </pre>
 *
 * <h2>Por qué no es una transacción</h2>
 *
 * El paso 2 habla con un sistema externo que no participa en la transacción de PostgreSQL. No hay
 * ningún {@code @Transactional} que pueda abarcar los tres pasos, y tampoco lo habría si mañana
 * Inventario fuese un servicio propio.
 *
 * <p>Una saga sustituye la atomicidad por <strong>compensaciones</strong>: cada paso tiene una
 * acción que deshace su efecto. Y no son simétricas — no se puede «des-cobrar» una tarjeta, se
 * emite un reembolso, que es un hecho nuevo. Por eso una saga es <em>eventualmente</em> coherente
 * y no atómica: hay instantes en los que el sistema está a medias, y el diseño tiene que
 * soportarlo.
 *
 * <h2>Por qué orquestación y no coreografía</h2>
 *
 * Aquí el flujo se lee de arriba abajo en un método. Con coreografía —cada contexto reaccionando a
 * los eventos de los demás— el flujo no está escrito en ningún sitio: para saber qué ocurre hay
 * que reconstruirlo leyendo a qué evento responde cada consumidor, y los ciclos son fáciles de
 * crear sin querer.
 *
 * <p>El precio es que esta clase conoce Inventario y Pagos, y eso es acoplamiento. Es un
 * acoplamiento consciente y localizado en un solo punto, que suele ser mejor negocio que un
 * flujo repartido por seis clases que nadie puede seguir.
 *
 * <h2>Cada paso, en su propia transacción</h2>
 *
 * Nótese que la clase NO lleva {@code @Transactional}. Es deliberado: una transacción abierta
 * durante la llamada al cobro retendría una conexión del pool durante segundos —el bug número 8
 * del módulo 4—, y con 20 conexiones bastarían 20 compras simultáneas para tumbar la aplicación
 * entera. Cada paso abre y cierra la suya.
 */
@ApplicationScoped
public class PurchaseSaga {

    private static final Logger LOG = Logger.getLogger(PurchaseSaga.class);

    private final Inventory inventory;
    private final PaymentGateway payments;

    PurchaseSaga(Inventory inventory, PaymentGateway payments) {
        this.inventory = inventory;
        this.payments = payments;
    }

    /**
     * Ejecuta la compra completa.
     *
     * @throws PaymentDeclinedException si el pago se rechaza, después de haber compensado
     */
    public PurchaseResult buy(ListingId listingId, BuyerId buyerId, int units, Money total) {
        // PASO 1 — reservar. Si no hay stock, la excepción sale sin nada que compensar: aún no
        // se ha hecho nada.
        Reservation reserva = reserve(listingId, buyerId, units);

        String chargeId;
        try {
            // PASO 2 — cobrar. Fuera de transacción, hablando con un sistema externo.
            //
            // La clave de idempotencia es el id de la reserva, y esa elección importa: si la red
            // se cae después de que el cargo se procese pero antes de recibir la respuesta, el
            // reintento con la misma clave devuelve el cargo original en lugar de cobrar dos
            // veces. Un UUID nuevo por intento daría cobros duplicados.
            chargeId = payments.charge(reserva.id().toString(), total);
        } catch (RuntimeException fallo) {
            // COMPENSACIÓN — devolver las unidades al inventario.
            //
            // Se hace en un try aparte a propósito: si la compensación también falla, lo que no
            // puede pasar es que ese fallo tape el motivo original. El comprador tiene derecho a
            // saber que le rechazaron la tarjeta, no a recibir un error de inventario.
            compensate(reserva, fallo);
            throw fallo;
        }

        try {
            // PASO 3 — confirmar. Las unidades salen definitivamente del almacén.
            inventory.confirm(reserva.id());
        } catch (RuntimeException fallo) {
            // Aquí ya se ha cobrado, así que la compensación es un reembolso. Es el peor punto
            // de fallo de toda la saga: el dinero ya cambió de manos.
            LOG.errorf(fallo, "Confirmation failed after charging %s, refunding", chargeId);
            payments.refund(chargeId);
            throw fallo;
        }

        return new PurchaseResult(reserva.id().toString(), chargeId, units, total);
    }

    /**
     * Paso 1 en su propia transacción.
     *
     * <p>Tiene que ser un método aparte y no un bloque dentro de {@code buy}: los interceptores
     * de CDI solo actúan en llamadas que entran desde fuera del bean, así que un
     * {@code @Transactional} invocado desde otro método de esta misma clase se ejecutaría sin
     * transacción ninguna. Es la misma regla que obligó a crear {@code TransactionalRunner} en
     * los tests del módulo 6.
     */
    @Transactional
    Reservation reserve(ListingId listingId, BuyerId buyerId, int units) {
        return inventory.reserve(listingId, buyerId, units);
    }

    private void compensate(Reservation reserva, RuntimeException causaOriginal) {
        try {
            inventory.cancel(reserva.id());
        } catch (RuntimeException fallaLaCompensacion) {
            // Una compensación fallida deja inventario apartado que nadie va a comprar. No es
            // catastrófico porque la reserva caduca sola y el barrido del módulo 6 la devolverá:
            // esa es la red de seguridad que hace tolerable este caso. Se registra en ERROR
            // porque, si ocurre a menudo, hay un problema mayor detrás.
            LOG.errorf(fallaLaCompensacion,
                    "Compensation failed for reservation %s; it will be released when it expires",
                    reserva.id());
            causaOriginal.addSuppressed(fallaLaCompensacion);
        }
    }

    /** Lo que devuelve una compra completada. */
    public record PurchaseResult(String reservationId, String chargeId, int units, Money total) {
    }
}
