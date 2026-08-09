package com.marketplace.shared.outbox;

import com.marketplace.catalog.domain.ListingId;
import com.marketplace.inventory.application.Inventory;
import com.marketplace.inventory.domain.BuyerId;
import com.marketplace.inventory.domain.InsufficientStockException;
import com.marketplace.support.DatabaseCleaner;
import com.marketplace.support.TransactionalRunner;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.inventory.domain.event.StockChanged;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lo único que hay que demostrar del outbox: <strong>que el evento y el cambio de negocio van
 * juntos o no van</strong>.
 *
 * <p>Todo lo demás del patrón —el relay, Kafka, el consumidor— es maquinaria. Si esta garantía
 * falla, la maquinaria transporta mentiras muy eficientemente.
 */
@QuarkusTest
@DisplayName("Outbox transaccional")
class OutboxTest {

    private static final BuyerId COMPRADOR = new BuyerId(UUID.randomUUID());

    @Inject
    Inventory inventory;

    @Inject
    EntityManager entityManager;

    @Inject
    DatabaseCleaner database;

    @Inject
    TransactionalRunner tx;

    @Inject
    OutboxRelay relay;

    @Inject
    ObjectMapper json;

    @BeforeEach
    void setUp() {
        database.clear();
    }

    /*
     * Limpiar también AL TERMINAR, no solo antes.
     *
     * Estos tests necesitan transacciones reales, así que no pueden usar @TestTransaction ni
     * apoyarse en su rollback: lo que escriben, queda. Los tests más antiguos —los que cuentan
     * filas con @TestTransaction— asumen una base vacía, así que estos residuos los hacían
     * fallar a distancia, en otra clase y por un motivo que no aparecía por ningún lado.
     *
     * Regla general: quien no puede deshacer lo que escribe, recoge al salir.
     */
    @AfterEach
    void tearDown() {
        database.clear();
    }


    private long eventosDe(ListingId listingId) {
        return tx.call(() -> entityManager.createQuery("""
                        select count(e) from OutboxEventEntity e where e.aggregateId = :id
                        """, Long.class)
                .setParameter("id", listingId.value())
                .getSingleResult());
    }

    @Test
    @DisplayName("una operación de negocio deja su evento en la bandeja")
    void businessChangeLeavesAnEvent() {
        var listingId = ListingId.newId();
        tx.run(() -> inventory.track(listingId, 10));

        assertEquals(1, eventosDe(listingId));
    }

    @Test
    @DisplayName("cada paso del ciclo de compra genera su evento")
    void everyStepEmitsAnEvent() {
        var listingId = ListingId.newId();
        tx.run(() -> inventory.track(listingId, 10));
        var reserva = tx.call(() -> inventory.reserve(listingId, COMPRADOR, 2));
        tx.run(() -> inventory.confirm(reserva.id()));

        assertEquals(3, eventosDe(listingId), "alta, reserva y confirmación");
    }

    @Test
    @DisplayName("LA GARANTÍA: si la operación falla, el evento tampoco queda")
    void aFailedOperationLeavesNoEvent() {
        var listingId = ListingId.newId();
        tx.run(() -> inventory.track(listingId, 1));
        long antes = eventosDe(listingId);

        // Esta reserva no cabe, así que la transacción entera se deshace.
        assertThrows(InsufficientStockException.class,
                () -> tx.run(() -> inventory.reserve(listingId, COMPRADOR, 5)));

        // Y con ella el evento. Sin esta propiedad, el catálogo acabaría anunciando un stock
        // que nunca se descontó: un evento de algo que no ocurrió es peor que no tener evento.
        assertEquals(antes, eventosDe(listingId), "no puede quedar el evento de un cambio revertido");
    }

    @Test
    @DisplayName("los eventos nacen pendientes y llevan la clave de partición correcta")
    void eventsStartPendingWithTheAggregateAsKey() throws Exception {
        var listingId = ListingId.newId();
        tx.run(() -> inventory.track(listingId, 10));

        var evento = tx.call(() -> entityManager.createQuery("""
                        select e from OutboxEventEntity e where e.aggregateId = :id
                        """, OutboxEventEntity.class)
                .setParameter("id", listingId.value())
                .getSingleResult());

        assertEquals("stock", evento.aggregateType);
        assertEquals("StockChanged", evento.eventType);
        // De esto depende que los eventos de una misma publicación lleguen ORDENADOS al
        // consumidor: Kafka solo garantiza el orden dentro de una partición.
        assertEquals(listingId.value(), evento.aggregateId);
        assertNull(evento.publishedAt, "nace pendiente de publicar");

        // Sobre el objeto deserializado y no sobre el JSON crudo: comprobar subcadenas del
        // payload ata el test al formato exacto que produzca Jackson —espacios, orden de
        // campos— y rompe por motivos que no tienen nada que ver con lo que se quiere probar.
        var payload = json.readValue(evento.payload, StockChanged.class);
        assertEquals(10, payload.available());
        assertEquals(0, payload.reserved());
    }

    @Test
    @DisplayName("el relay publica lo pendiente y lo marca, sin repetirlo")
    void relayPublishesAndMarks() {
        var listingId = ListingId.newId();
        tx.run(() -> inventory.track(listingId, 10));

        int publicados = tx.call(() -> entityManager.createQuery("""
                        select count(e) from OutboxEventEntity e where e.publishedAt is null
                        """, Long.class).getSingleResult()).intValue();
        assertTrue(publicados >= 1, "debería haber al menos un evento pendiente");

        // El relay corre solo cada segundo; aquí se invoca a mano para no depender del reloj.
        // Un test que espere a un @Scheduled es lento y, sobre todo, intermitente.
        relay.publishPending();

        long pendientes = tx.call(() -> entityManager.createQuery("""
                        select count(e) from OutboxEventEntity e where e.publishedAt is null
                        """, Long.class).getSingleResult());
        assertEquals(0, pendientes, "el relay debe dejar la bandeja vacía");

        // Segunda pasada: no hay nada que hacer. Publicar dos veces lo mismo es precisamente lo
        // que el marcado evita, aunque el consumidor deba estar preparado igualmente por si el
        // relay muere entre publicar y marcar.
        assertEquals(0, relay.publishPending());
    }

}
