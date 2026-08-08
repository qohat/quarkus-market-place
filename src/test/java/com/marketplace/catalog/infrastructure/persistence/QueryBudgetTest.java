package com.marketplace.catalog.infrastructure.persistence;

import com.marketplace.catalog.domain.Listing;
import com.marketplace.catalog.domain.ListingRepository;
import com.marketplace.catalog.domain.ListingStatus;
import com.marketplace.catalog.domain.ProductListing;
import com.marketplace.shared.domain.Money;
import com.marketplace.shared.domain.PageRequest;
import com.marketplace.shared.domain.SellerId;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Presupuesto de consultas: fija cuántas sentencias SQL puede emitir cada operación.
 *
 * <h2>Qué es el problema N+1 y por qué merece su propio tipo de test</h2>
 *
 * Cargar una lista de N elementos y luego, sin darse cuenta, lanzar una consulta adicional por
 * cada uno: 1 consulta para la lista más N para el detalle. Con 20 elementos en desarrollo pasa
 * desapercibido; con 5.000 en producción, el endpoint tarda treinta segundos.
 *
 * <p>Lo insidioso es que <strong>no falla</strong>. No hay excepción, no hay error en los logs,
 * los tests funcionales pasan. Solo hay lentitud, y aparece cuando ya hay datos reales.
 *
 * <p>Suele colarse por dos caminos:
 * <ul>
 *   <li>Una asociación {@code LAZY} que se recorre dentro de un bucle: cada acceso dispara su
 *       propio SELECT.</li>
 *   <li>Un bucle que llama al repositorio, que es el caso que reproducimos abajo. Este no lo
 *       provoca Hibernate: lo escribe una persona, y por eso ninguna configuración lo evita.</li>
 * </ul>
 *
 * <p>Las defensas habituales —{@code join fetch}, {@code @BatchSize}, {@code EntityGraph}— sirven
 * para el primer caso. Para el segundo solo sirve verlo, y esa es exactamente la función de estos
 * tests: convierten un problema de rendimiento invisible en un build rojo.
 */
@QuarkusTest
@DisplayName("Presupuesto de consultas SQL")
class QueryBudgetTest {

    @Inject
    ListingRepository repository;

    @Inject
    EntityManager entityManager;

    private static final Money PRICE = Money.of("25.00", "EUR");

    private Statistics estadisticas() {
        return entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getStatistics();
    }

    /** Ejecuta la acción y devuelve cuántas sentencias JDBC hicieron falta. */
    private long consultasDe(Runnable accion) {
        entityManager.flush();          // que no cuenten escrituras pendientes de antes
        var stats = estadisticas();
        stats.clear();

        accion.run();
        entityManager.flush();

        return stats.getPrepareStatementCount();
    }

    private void crear(int cuantos) {
        var seller = SellerId.newId();
        for (int i = 0; i < cuantos; i++) {
            repository.save(ProductListing
                    .draft(seller, "Teclado " + i, PRICE, 1)
                    .withStatus(ListingStatus.PUBLISHED));
        }
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @TestTransaction
    @DisplayName("listar el catálogo cuesta 2 consultas, independientemente del tamaño")
    void browsingCostsTwoQueriesRegardlessOfSize() {
        crear(50);

        long consultas = consultasDe(() -> repository.findVisible(PageRequest.of(0, 50)));

        // Exactamente dos: el COUNT del total y el SELECT de la página. Ni una por fila.
        assertEquals(2, consultas,
                "listar debería costar 2 consultas (count + select), costó " + consultas);
    }

    @Test
    @TestTransaction
    @DisplayName("el coste no crece con el número de resultados")
    void costDoesNotGrowWithResultCount() {
        crear(5);
        long conCinco = consultasDe(() -> repository.findVisible(PageRequest.of(0, 100)));

        entityManager.clear();
        crear(80);
        long conOchentaYCinco = consultasDe(() -> repository.findVisible(PageRequest.of(0, 100)));

        // La firma de un N+1 es justo lo contrario: que este número suba con los datos.
        assertEquals(conCinco, conOchentaYCinco,
                "el número de consultas cambió con el volumen de datos: huele a N+1");
    }

    @Test
    @TestTransaction
    @DisplayName("repetir findById tras listar NO produce N+1: lo evita la caché de sesión")
    void findByIdAfterListingIsFreeThanksToTheCache() {
        crear(20);

        long consultas = consultasDe(() -> {
            List<Listing> pagina = repository.findVisible(PageRequest.of(0, 20)).items();
            for (Listing listing : pagina) {
                repository.findById(listing.id());   // parece un N+1 de manual...
            }
        });

        // ...y sin embargo son 2. Las 20 entidades ya están en la caché de primer nivel desde
        // que las cargó findVisible, así que findById las devuelve sin tocar la base de datos.
        //
        // Este resultado explica por qué el N+1 es tan escurridizo: el mismo bucle puede ser
        // gratis o catastrófico según lo que haya cargado antes en la sesión. Un cambio
        // aparentemente inocuo aguas arriba —vaciar la sesión, cambiar el orden de dos
        // llamadas, partir un método en dos transacciones— lo enciende sin tocar el bucle.
        assertEquals(2, consultas,
                "la caché de primer nivel debería absorber los findById, hubo " + consultas);
    }

    @Test
    @TestTransaction
    @DisplayName("así se ve un N+1 de verdad")
    void thisIsWhatAnNPlusOneLooksLike() {
        crear(20);

        long consultas = consultasDe(() -> {
            // EL ANTIPATRÓN, en su forma realista: para cada publicación de la página se
            // consulta algo que la caché no puede servir —aquí, cuántas publicaciones tiene su
            // vendedor, para pintar un "y 12 más de este vendedor" en la tarjeta.
            //
            // Nadie escribe esto queriendo hacer 21 consultas. Se escribe porque la vista pedía
            // un dato más y el bucle ya estaba ahí.
            List<Listing> pagina = repository.findVisible(PageRequest.of(0, 20)).items();
            for (Listing listing : pagina) {
                repository.findBySeller(listing.sellerId(), PageRequest.of(0, 1));
            }
        });

        //   correcto  ->   2 consultas
        //   N+1       ->  40+ consultas (2 por elemento: count + select)
        //
        // Y el resultado devuelto al usuario es EXACTAMENTE EL MISMO. Por eso ningún test
        // funcional lo detecta, y solo un presupuesto de consultas lo caza.
        assertTrue(consultas > 20,
                "se esperaba un N+1 con más de 20 consultas, hubo " + consultas);

        // La solución en un caso así no es tocar el bucle, sino resolverlo de una vez: una
        // única consulta agregada (GROUP BY seller_id) para toda la página.
    }

    @Test
    @TestTransaction
    @DisplayName("buscar una publicación por id cuesta exactamente 1 consulta")
    void findByIdCostsOneQuery() {
        var listing = ProductListing.draft(SellerId.newId(), "Teclado", PRICE, 1);
        repository.save(listing);
        entityManager.flush();
        entityManager.clear();

        long consultas = consultasDe(() -> repository.findById(listing.id()));

        assertEquals(1, consultas);
    }

    @Test
    @TestTransaction
    @DisplayName("la caché de primer nivel evita repetir la misma consulta")
    void firstLevelCacheAvoidsRepeatedQueries() {
        var listing = ProductListing.draft(SellerId.newId(), "Teclado", PRICE, 1);
        repository.save(listing);
        entityManager.flush();
        entityManager.clear();

        long consultas = consultasDe(() -> {
            repository.findById(listing.id());
            repository.findById(listing.id());
            repository.findById(listing.id());
        });

        // Una sola consulta: dentro de una misma sesión, Hibernate mantiene una caché de
        // primer nivel y devuelve la entidad ya cargada sin volver a la base de datos.
        //
        // Es un arma de doble filo: en un proceso largo que recorra muchas entidades, esa
        // caché crece sin parar y se convierte en una fuga de memoria. De ahí los
        // entityManager.clear() periódicos en los procesos por lotes.
        assertEquals(1, consultas,
                "la caché de primer nivel debería servir las repeticiones");
    }
}
