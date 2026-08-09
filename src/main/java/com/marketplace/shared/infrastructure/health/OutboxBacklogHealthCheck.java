package com.marketplace.shared.infrastructure.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Vigila que la bandeja de salida no se esté acumulando.
 *
 * <h2>Por qué {@code @Readiness} y no {@code @Liveness}</h2>
 *
 * Es la distinción que más se confunde, y equivocarse tiene consecuencias graves en Kubernetes:
 *
 * <pre>
 *   liveness   ¿estoy vivo?    Si falla → REINICIAR el contenedor
 *   readiness  ¿puedo servir?  Si falla → sacarme del balanceador, sin reiniciar
 * </pre>
 *
 * Un outbox atascado no se arregla reiniciando: si esto fuera un {@code @Liveness}, Kubernetes
 * entraría en un bucle de reinicios que <strong>empeoraría</strong> el atasco, porque cada
 * arranque cuesta tiempo durante el cual nadie vacía la cola.
 *
 * <p>La regla: <strong>liveness solo para lo que un reinicio pueda arreglar</strong> —un
 * interbloqueo, una fuga de memoria—. Todo lo demás, incluidas las dependencias externas, es
 * readiness. Y ojo con meter dependencias en liveness: si la base de datos se cae, Kubernetes
 * reiniciaría todas tus instancias a la vez, convirtiendo una incidencia en una caída total.
 */
@Readiness
@ApplicationScoped
public class OutboxBacklogHealthCheck implements HealthCheck {

    /**
     * A partir de aquí, el relay no da abasto. El número no es mágico: sale de que el relay
     * publica lotes de 100 cada segundo, así que una acumulación sostenida por encima de mil
     * significa que entra más de lo que sale.
     */
    private static final long UMBRAL = 1000;

    private final EntityManager entityManager;

    OutboxBacklogHealthCheck(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public HealthCheckResponse call() {
        long pendientes = entityManager.createQuery(
                        "select count(e) from OutboxEventEntity e where e.publishedAt is null",
                        Long.class)
                .getSingleResult();

        return HealthCheckResponse.named("outbox-backlog")
                .status(pendientes < UMBRAL)
                // El dato concreto va en la respuesta: un health check que solo dice «mal» obliga
                // a ir a buscar por qué. Este dice cuántos hay pendientes y desde qué umbral
                // preocuparse, que es lo que hace falta a las tres de la mañana.
                .withData("pending", pendientes)
                .withData("threshold", UMBRAL)
                .build();
    }
}
