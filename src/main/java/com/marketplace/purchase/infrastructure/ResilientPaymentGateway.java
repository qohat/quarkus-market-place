package com.marketplace.purchase.infrastructure;

import com.marketplace.purchase.domain.PaymentDeclinedException;
import com.marketplace.purchase.domain.PaymentGateway;
import com.marketplace.shared.domain.Money;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

import java.time.temporal.ChronoUnit;

/**
 * Envuelve la pasarela con las protecciones que necesita quien la llama.
 *
 * <p>La resiliencia va aquí y no en {@link FakePaymentGateway} porque <strong>no es una propiedad
 * del dependiente sino una decisión de quien lo usa</strong>: dos aplicaciones que llamen a la
 * misma pasarela pueden querer políticas distintas.
 *
 * <h2>El orden de los interceptores, que no es negociable</h2>
 *
 * MicroProfile Fault Tolerance los aplica siempre de fuera hacia dentro:
 *
 * <pre>
 *   Fallback  &gt;  Retry  &gt;  CircuitBreaker  &gt;  Timeout  &gt;  Bulkhead
 * </pre>
 *
 * Que <strong>Retry envuelva a CircuitBreaker</strong> es lo que impide que los dos patrones se
 * peleen: cada reintento pasa por el circuito, así que en cuanto este abre, los reintentos dejan
 * de llegar al servicio. Con el orden contrario, el retry seguiría machacando a un dependiente que
 * ya se había declarado caído.
 *
 * <p>Y que <strong>Timeout esté por dentro de Retry</strong> significa que el límite es por
 * intento, no total: 3 intentos de 2 segundos pueden tardar 6. Es la confusión más común al
 * configurar esto, y la que hace que un cliente espere el triple de lo que su autor creía.
 */
@ApplicationScoped
public class ResilientPaymentGateway implements PaymentGateway {

    private static final Logger LOG = Logger.getLogger(ResilientPaymentGateway.class);

    private final FakePaymentGateway delegate;
    private final Counter cobros;
    private final Counter rechazos;
    private final Counter fallosTecnicos;

    ResilientPaymentGateway(FakePaymentGateway delegate, MeterRegistry registry) {
        this.delegate = delegate;
        // MÉTRICAS DE NEGOCIO, con etiquetas de CARDINALIDAD ACOTADA.
        //
        // `result` toma tres valores y punto. Poner aquí el id del comprador crearía una serie
        // temporal por persona y haría explotar Prometheus: es un error carísimo, muy común, y
        // que no se nota hasta que la instancia de monitorización se queda sin memoria.
        //
        // La regla: las etiquetas son para dimensiones acotadas; los identificadores van en las
        // trazas y en los logs, que sí están pensados para alta cardinalidad.
        this.cobros = Counter.builder("marketplace.payments")
                .tag("result", "charged").register(registry);
        this.rechazos = Counter.builder("marketplace.payments")
                .tag("result", "declined").register(registry);
        this.fallosTecnicos = Counter.builder("marketplace.payments")
                .tag("result", "unavailable").register(registry);
    }

    /**
     * Cobra, con las tres protecciones.
     *
     * <h3>{@code @Timeout}</h3>
     * Lo primero que hay que poner y lo que más se olvida. <strong>Un servicio lento es peor que
     * uno caído</strong>: el caído falla rápido, el lento retiene tus hilos y tus conexiones hasta
     * arrastrarte con él. Sin timeout no hay resiliencia posible, porque ninguna otra protección
     * llega a activarse.
     *
     * <h3>{@code @Retry}</h3>
     * Con {@code jitter}, que es la parte que casi nadie pone. Sin él, todos los clientes
     * reintentan al mismo tiempo y crean picos sincronizados que rematan al servicio justo cuando
     * intentaba recuperarse: el rebaño atronador. El jitter reparte esos reintentos.
     *
     * <p>Y lo decisivo: {@code abortOn = PaymentDeclinedException.class}. Un pago rechazado es un
     * resultado de negocio, no un fallo técnico. Reintentarlo es inútil, gasta cuota y puede
     * disparar los controles antifraude de la pasarela.
     *
     * <h3>{@code @CircuitBreaker}</h3>
     * Tras un 50 % de fallos en las últimas 8 llamadas, deja de llamar durante 5 segundos y falla
     * al instante. <strong>No protege a la pasarela: te protege a ti</strong>, evitando que te
     * caigas esperando a alguien que ya está muerto.
     *
     * <p>{@code skipOn} es imprescindible: sin él, una racha de pagos legítimamente rechazados
     * abriría el circuito e impediría cobrar a quien sí tiene fondos. El circuito debe contar
     * fallos de <em>infraestructura</em>, nunca resultados de negocio.
     */
    @Override
    @Timeout(value = 2, unit = ChronoUnit.SECONDS)
    @Retry(
            maxRetries = 3,
            delay = 200,
            jitter = 100,
            retryOn = PaymentGatewayUnavailableException.class,
            abortOn = PaymentDeclinedException.class)
    @CircuitBreaker(
            requestVolumeThreshold = 8,
            failureRatio = 0.5,
            delay = 5000,
            successThreshold = 2,
            skipOn = PaymentDeclinedException.class)
    public String charge(String idempotencyKey, Money amount) {
        try {
            var chargeId = delegate.charge(idempotencyKey, amount);
            cobros.increment();
            return chargeId;
        } catch (PaymentDeclinedException rechazado) {
            rechazos.increment();
            throw rechazado;
        } catch (RuntimeException tecnico) {
            fallosTecnicos.increment();
            LOG.warnf("Payment attempt failed: %s", tecnico.getMessage());
            throw tecnico;
        }
    }

    /**
     * El reembolso es una COMPENSACIÓN, y eso cambia la política.
     *
     * <p>Aquí se reintenta más veces y durante más tiempo que en el cobro: si un reembolso se
     * pierde, el comprador se queda sin su dinero, que es mucho peor que una compra que no llega a
     * hacerse. No lleva circuit breaker por lo mismo — abrirlo dejaría reembolsos sin emitir.
     *
     * <p>Es un ejemplo de que <strong>la política de resiliencia depende de la consecuencia del
     * fallo, no de la operación</strong>. Copiar las mismas anotaciones a todos los métodos es el
     * error de bulto de este módulo.
     */
    @Override
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @Retry(maxRetries = 5, delay = 500, jitter = 200)
    public void refund(String chargeId) {
        delegate.refund(chargeId);
    }
}
