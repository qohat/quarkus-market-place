package com.marketplace.support;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Deja la base de datos vacía entre tests.
 *
 * <p><strong>Por qué no basta {@code @TestTransaction}.</strong> Quarkus ofrece esa anotación,
 * que envuelve el test en una transacción y la revierte al terminar — la forma más limpia de
 * aislar. Pero solo funciona cuando el test y el código bajo prueba comparten transacción, y en
 * los tests de REST no la comparten: RestAssured hace una petición HTTP real, que el servidor
 * atiende en otro hilo con su propia transacción. El rollback del test no alcanza a lo que
 * escribió el endpoint.
 *
 * <p>Así que para esos hay que limpiar explícitamente. Para los tests que llaman al repositorio
 * en el mismo hilo, {@code @TestTransaction} sigue siendo mejor opción.
 *
 * <p>Se usa {@code delete from} en JPQL en lugar de {@code TRUNCATE}: no requiere permisos
 * especiales y no reinicia secuencias que otros tests podrían estar observando.
 */
@ApplicationScoped
public class DatabaseCleaner {

    private final EntityManager entityManager;

    DatabaseCleaner(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional
    public void clear() {
        entityManager.createQuery("delete from ListingEntity").executeUpdate();
    }
}
