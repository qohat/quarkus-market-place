package com.marketplace.support;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.function.Supplier;

/**
 * Ejecuta una operación en su propia transacción.
 *
 * <p>Existe para poder probar concurrencia de verdad. {@code @TestTransaction} no sirve aquí: el
 * test necesita <strong>varias transacciones simultáneas compitiendo entre sí</strong>, no una
 * que se revierta al final. Y llamar directamente al repositorio desde un hilo suelto tampoco,
 * porque sin transacción activa el {@code EntityManager} ni siquiera se puede usar.
 *
 * <p>Tiene que ser un bean aparte por una regla de CDI que muerde a todo el mundo alguna vez:
 * <strong>los interceptores solo actúan en llamadas que entran desde fuera del bean.</strong> Un
 * método {@code @Transactional} invocado desde otro método de la misma clase se ejecuta sin
 * transacción ninguna, porque la llamada no pasa por el proxy. Por eso este ayudante vive en su
 * propia clase.
 *
 * <p>{@code REQUIRES_NEW} no haría falta —cada hilo llega sin transacción— pero se deja explícito
 * para que quede claro que cada ejecución quiere la suya, que es justo lo que se está probando.
 */
@ApplicationScoped
public class TransactionalRunner {

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public <T> T call(Supplier<T> operation) {
        return operation.get();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void run(Runnable operation) {
        operation.run();
    }
}
