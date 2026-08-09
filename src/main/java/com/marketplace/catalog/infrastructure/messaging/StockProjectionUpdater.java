package com.marketplace.catalog.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.inventory.domain.event.StockChanged;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * Mantiene al día la copia de stock que el catálogo enseña en el escaparate.
 *
 * <p>Cierra el cabo suelto del módulo 6: allí se decidió que el catálogo conservara un
 * {@code availableStock} propio, degradado a dato de presentación que puede ir atrasado, y que
 * <strong>lo sincronizara un evento</strong>. Este es ese evento llegando.
 *
 * <h2>Consistencia eventual, dicha en voz alta</h2>
 *
 * Entre que Inventario confirma una venta y este método se ejecuta pasan unos segundos: el
 * intervalo del relay más la latencia de Kafka. Durante esa ventana, el catálogo enseña un número
 * antiguo. <strong>Es aceptable y es el diseño</strong>, porque un número mostrado siempre está
 * atrasado —para cuando llega a la pantalla del comprador ya ha cambiado—, y porque nadie decide
 * nada con él: reservar va contra Inventario, que sí es la verdad.
 *
 * <h2>Por qué {@code @Blocking}</h2>
 *
 * Y aquí vuelve el módulo 4. Los consumidores de Reactive Messaging se ejecutan sobre un event
 * loop de Vert.x, y este método hace JDBC, que bloquea. Sin {@code @Blocking} sería el bug número
 * 1 del módulo 4 en su forma más pura: un consumidor de Kafka parando los event loops de toda la
 * aplicación, incluidas las peticiones HTTP que no tienen nada que ver.
 *
 * <p>Es además de los sitios donde más se cuela, porque el consumidor no «parece» un endpoint y
 * nadie piensa en qué hilo corre.
 *
 * <h2>Idempotencia sin llevar registro</h2>
 *
 * El outbox garantiza at-least-once, así que este método <strong>va a recibir duplicados</strong>.
 * No hace falta una tabla de mensajes vistos: como {@link StockChanged} lleva el estado resultante
 * y no un incremento, aplicarlo dos veces deja exactamente el mismo valor. Idempotencia por la
 * forma del mensaje, que es la más barata de todas.
 */
@ApplicationScoped
public class StockProjectionUpdater {

    private static final Logger LOG = Logger.getLogger(StockProjectionUpdater.class);

    private final EntityManager entityManager;
    private final ObjectMapper json;

    StockProjectionUpdater(EntityManager entityManager, ObjectMapper json) {
        this.entityManager = entityManager;
        this.json = json;
    }

    @Incoming("events-in")
    @Blocking
    @Transactional
    public void onEvent(String payload) {
        StockChanged evento;
        try {
            evento = json.readValue(payload, StockChanged.class);
        } catch (Exception noEsDeEsteTipo) {
            // Todos los eventos comparten tema, así que aquí llega de todo. Un mensaje que no
            // encaja no es un error: es de otro. Descartarlo en silencio es correcto MIENTRAS
            // el tema sea nuestro; con temas compartidos entre equipos convendría enrutar por
            // el tipo de evento en vez de intentar deserializar y ver qué pasa.
            LOG.debugf("Ignored event that is not a StockChanged: %s", noEsDeEsteTipo.getMessage());
            return;
        }

        // Actualización directa por SQL en lugar de cargar la entidad: es una proyección, no
        // una decisión de negocio. No hay invariante que comprobar ni estado previo que
        // considerar, solo un número que se sobrescribe.
        int filas = entityManager.createQuery("""
                        update ProductListingEntity l
                           set l.availableStock = :available
                         where l.id = :id
                        """)
                .setParameter("available", evento.available())
                .setParameter("id", UUID.fromString(evento.listingId()))
                .executeUpdate();

        if (filas == 0) {
            // Puede ocurrir legítimamente: un servicio reservable no tiene stock, y una
            // publicación borrada tampoco. No es motivo para reintentar ni para alarmarse.
            LOG.debugf("No product listing to update for %s", evento.listingId());
        }
    }
}
