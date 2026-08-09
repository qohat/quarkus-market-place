package com.marketplace.purchase.infrastructure;

import com.marketplace.purchase.domain.PaymentDeclinedException;
import com.marketplace.purchase.domain.PaymentGateway;
import com.marketplace.shared.domain.Money;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * La pasarela remota simulada: hace de Stripe.
 *
 * <p>No lleva ninguna anotación de tolerancia a fallos, y es deliberado: esta clase representa
 * <strong>el sistema externo</strong>, con sus fallos y su lentitud. Quien se protege de ellos es
 * {@link ResilientPaymentGateway}, que la envuelve. Separarlos deja claro que la resiliencia no es
 * una propiedad del dependiente sino una decisión de quien lo llama.
 *
 * <h2>Lo único que reproduce fielmente</h2>
 *
 * <strong>La idempotencia por clave.</strong> Todas las pasarelas reales la ofrecen, y es lo que
 * hace seguro reintentar: si la red se cae después de que el cargo se procese pero antes de
 * recibir la respuesta, el reintento con la misma clave devuelve el cargo original en lugar de
 * cobrar dos veces. Sin ella, cualquier política de reintentos duplicaría cobros.
 *
 * <h2>Los modos de fallo</h2>
 *
 * Se controlan desde los tests para poder provocar cada escenario a voluntad. Un sistema externo
 * falla de tres formas distintas, y confundirlas es el origen de casi todas las políticas de
 * reintento mal puestas:
 *
 * <ul>
 *   <li>{@link Mode#SLOW} — responde, pero tarde. El caso que hace falta el timeout.</li>
 *   <li>{@link Mode#TRANSIENT_FAILURE} — falla ahora y funciona luego. Reintentar tiene sentido.</li>
 *   <li>{@link Mode#DECLINE} — rechaza el pago. Reintentar NO tiene sentido: fallará igual.</li>
 * </ul>
 */
@ApplicationScoped
@Typed(FakePaymentGateway.class)
public class FakePaymentGateway implements PaymentGateway {

    private static final Logger LOG = Logger.getLogger(FakePaymentGateway.class);

    public enum Mode {
        /** Funciona con normalidad. */
        HEALTHY,
        /** Tarda {@code delayMillis} en responder. */
        SLOW,
        /** Lanza un fallo técnico: red, 503, conexión rechazada. Es reintentable. */
        TRANSIENT_FAILURE,
        /** Rechaza el pago. Es un resultado de negocio y NO es reintentable. */
        DECLINE
    }

    private final Map<String, String> charges = new ConcurrentHashMap<>();
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.HEALTHY);
    private final AtomicInteger delayMillis = new AtomicInteger(3000);

    /** Cuántas veces se ha ENTRADO de verdad, incluidos los intentos fallidos. */
    private final AtomicInteger attempts = new AtomicInteger();

    @Override
    public String charge(String idempotencyKey, Money amount) {
        attempts.incrementAndGet();

        switch (mode.get()) {
            case SLOW -> sleep(delayMillis.get());
            case TRANSIENT_FAILURE -> throw new PaymentGatewayUnavailableException(
                    "gateway temporarily unavailable");
            case DECLINE -> throw new PaymentDeclinedException("insufficient funds");
            case HEALTHY -> { /* seguir */ }
        }

        // La pieza que hace seguro reintentar: mismo id de cargo para la misma clave.
        var existente = charges.get(idempotencyKey);
        if (existente != null) {
            LOG.debugf("Idempotent replay of charge %s", existente);
            return existente;
        }
        // Regla arbitraria pero determinista, heredada del módulo 7: los céntimos .13 se
        // rechazan. Permite provocar el camino de compensación sin tocar el modo global.
        if (amount.amount().remainder(BigDecimal.ONE)
                .compareTo(new BigDecimal("0.13")) == 0) {
            throw new PaymentDeclinedException("insufficient funds");
        }
        var chargeId = "ch_" + UUID.randomUUID();
        charges.put(idempotencyKey, chargeId);
        return chargeId;
    }

    @Override
    public void refund(String chargeId) {
        LOG.infof("Refunded %s", chargeId);
        charges.values().remove(chargeId);
    }

    // ------------------------------------------------------------------ control desde tests

    public void behave(Mode newMode) {
        mode.set(newMode);
    }

    public void behave(Mode newMode, int delayMillis) {
        this.mode.set(newMode);
        this.delayMillis.set(delayMillis);
    }

    public void reset() {
        mode.set(Mode.HEALTHY);
        attempts.set(0);
        charges.clear();
    }

    /**
     * Intentos reales recibidos.
     *
     * <p>Es lo que permite demostrar que el retry reintenta y que el circuit breaker deja de
     * llamar: sin este contador, «el circuito está abierto» sería una afirmación sin prueba.
     */
    public int attempts() {
        return attempts.get();
    }

    public int chargeCount() {
        return charges.size();
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
