package com.marketplace.inventory.infrastructure.persistence;

import com.marketplace.catalog.domain.ListingId;
import com.marketplace.inventory.domain.BuyerId;
import com.marketplace.inventory.domain.SlotAlreadyBookedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;

import java.time.Instant;
import java.util.UUID;

/**
 * Reservas de franjas horarias: el otro problema de concurrencia del marketplace.
 *
 * <p>Un producto se agota con un contador; un servicio no. Dos personas pueden reservar la misma
 * clase el martes, pero no de 10:00 a 11:00 las dos. Aquí no hay una fila que decrementar: hay
 * que <strong>insertar</strong> comprobando algo sobre las filas que ya existen, y eso deja fuera
 * el truco del UPDATE atómico.
 *
 * <h2>Quien impide la doble reserva es PostgreSQL, no este código</h2>
 *
 * <pre>
 *   CONSTRAINT booking_no_overlap
 *       EXCLUDE USING gist (listing_id WITH =, slot WITH &amp;&amp;)
 * </pre>
 *
 * «No pueden existir dos filas con el mismo {@code listing_id} cuyos intervalos se solapen». La
 * comprobación ocurre dentro de la propia inserción, con la misma garantía que una clave única.
 *
 * <p>Este adaptador no consulta si la franja está libre: <strong>lo intenta</strong>, y si la
 * restricción salta, traduce el error técnico a un concepto de negocio. Consultar primero e
 * insertar después sería reintroducir la ventana entre leer y escribir, es decir, la sobreventa
 * con otro nombre.
 *
 * <p>Lo notable es el alcance de esa garantía: se mantiene aunque el código Java esté mal escrito,
 * aunque alguien inserte a mano desde {@code psql} y aunque haya veinte instancias desplegadas.
 * Ningún candado en Java llega tan lejos.
 *
 * <h2>Por qué SQL nativo</h2>
 *
 * {@code TSTZRANGE} no tiene equivalente en JPA estándar. Se podría escribir un tipo propio de
 * Hibernate, pero para dos consultas es más código y más cosas que explicar que la propia
 * consulta. Es un caso legítimo de bajar a SQL: el ORM cubre el 95 %, y para el 5 % restante
 * conviene saber salir.
 */
@ApplicationScoped
public class BookingRepository {

    /**
     * {@code tstzrange(inicio, fin, '[)')} construye un intervalo cerrado por la izquierda y
     * abierto por la derecha. Ese detalle es justo lo que hace que una reserva de 10:00 a 11:00 y
     * otra de 11:00 a 12:00 <strong>no</strong> se consideren solapadas: si el extremo derecho
     * fuera cerrado, no se podrían encadenar dos citas seguidas.
     */
    private static final String INSERT = """
            insert into booking (id, listing_id, buyer_id, slot, created_at)
            values (?1, ?2, ?3, tstzrange(?4, ?5, '[)'), now())
            """;

    /** Nombre de la restricción de exclusión, tal como la declara V5__create_booking_table.sql. */
    private static final String RESTRICCION = "booking_no_overlap";

    private final EntityManager entityManager;

    BookingRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Reserva una franja.
     *
     * @throws SlotAlreadyBookedException si se solapa con otra reserva de la misma publicación
     */
    public UUID book(ListingId listingId, BuyerId buyerId, Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("slot start must be before its end");
        }
        var id = UUID.randomUUID();
        try {
            entityManager.createNativeQuery(INSERT)
                    .setParameter(1, id)
                    .setParameter(2, listingId.value())
                    .setParameter(3, buyerId.value())
                    .setParameter(4, from)
                    .setParameter(5, to)
                    .executeUpdate();
            // El flush fuerza a que la sentencia llegue ya a PostgreSQL. Sin él, la violación de
            // la restricción saltaría al confirmar la transacción —fuera de este try— y se
            // propagaría como un error de infraestructura en vez de como «franja ocupada».
            entityManager.flush();
            return id;
        } catch (ConstraintViolationException e) {
            // El nombre de la restricción NO siempre llega en getConstraintName(): depende del
            // driver y del tipo de violación, y con las restricciones de exclusión de PostgreSQL
            // llega a null. Fiarse solo de ese campo hacía que la excepción se propagara sin
            // traducir y el cliente recibiera un 500 en lugar de «esa hora está ocupada».
            //
            // Por eso se mira también el mensaje, que sí incluye el nombre. Es menos elegante y
            // es lo que funciona.
            if (esSolapamiento(e)) {
                throw new SlotAlreadyBookedException(listingId, from, to);
            }
            throw e;
        }
    }

    private static boolean esSolapamiento(ConstraintViolationException e) {
        if (RESTRICCION.equals(e.getConstraintName())) {
            return true;
        }
        for (Throwable causa = e; causa != null; causa = causa.getCause()) {
            if (causa.getMessage() != null && causa.getMessage().contains(RESTRICCION)) {
                return true;
            }
        }
        return false;
    }

    /** Cuántas reservas tiene una publicación. Para comprobaciones y para el panel del vendedor. */
    public long countFor(ListingId listingId) {
        return ((Number) entityManager
                .createNativeQuery("select count(*) from booking where listing_id = ?1")
                .setParameter(1, listingId.value())
                .getSingleResult()).longValue();
    }
}
