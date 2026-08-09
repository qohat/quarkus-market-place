package com.marketplace.purchase.infrastructure;

import com.marketplace.purchase.domain.PaymentDeclinedException;
import com.marketplace.purchase.domain.PaymentGateway;
import com.marketplace.shared.domain.Money;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pasarela de pago simulada.
 *
 * <p>Sustituye a Stripe o similar, que quedan fuera del alcance del curso. Lo que sí reproduce es
 * lo único que importa para la saga: <strong>que el cobro es idempotente por clave</strong>. Ese
 * comportamiento no es un capricho de la simulación — es lo que ofrecen todas las pasarelas
 * reales y lo que hace seguro reintentar cuando la red falla en el peor momento.
 *
 * <p>Rechaza los importes con céntimos {@code .13} para poder provocar el camino de compensación
 * en los tests. Es arbitrario a propósito: cualquier regla vale mientras sea determinista.
 */
@ApplicationScoped
public class FakePaymentGateway implements PaymentGateway {

    private static final Logger LOG = Logger.getLogger(FakePaymentGateway.class);

    /** Cargos ya procesados, por clave de idempotencia. */
    private final Map<String, String> charges = new ConcurrentHashMap<>();

    @Override
    public String charge(String idempotencyKey, Money amount) {
        // LA PIEZA CLAVE: si ya se cobró con esta clave, se devuelve el cargo original sin
        // volver a cobrar. Es lo que convierte «reintentar es peligroso» en «reintentar es
        // seguro», y sin ello un timeout de red se traduce en un cobro duplicado.
        var existente = charges.get(idempotencyKey);
        if (existente != null) {
            LOG.debugf("Idempotent replay of charge %s", existente);
            return existente;
        }
        if (amount.amount().remainder(java.math.BigDecimal.ONE)
                .compareTo(new java.math.BigDecimal("0.13")) == 0) {
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

    /** Solo para tests: cuántos cargos distintos se han procesado. */
    public int chargeCount() {
        return charges.size();
    }
}
