package com.marketplace.inventory.infrastructure.persistence;

import com.marketplace.inventory.domain.Reservation;
import com.marketplace.inventory.domain.ReservationId;
import com.marketplace.inventory.domain.ReservationRepository;
import com.marketplace.inventory.domain.ReservationStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Adaptador JPA del puerto de reservas. */
@ApplicationScoped
public class PanacheReservationRepository implements ReservationRepository {

    private final EntityManager entityManager;

    PanacheReservationRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void save(Reservation reservation) {
        entityManager.persist(ReservationEntity.fromDomain(reservation));
    }

    @Override
    public Optional<Reservation> find(ReservationId id) {
        return Optional.ofNullable(entityManager.find(ReservationEntity.class, id.value()))
                .map(ReservationEntity::toDomain);
    }

    /**
     * No hay {@code merge} ni {@code persist}: la entidad ya está gestionada por la sesión, así
     * que basta con modificarla y el <em>dirty checking</em> de Hibernate emite el UPDATE al
     * confirmar la transacción.
     */
    @Override
    public void update(Reservation reservation) {
        var entity = entityManager.find(ReservationEntity.class, reservation.id().value());
        if (entity != null) {
            entity.updateFrom(reservation);
        }
    }

    /**
     * Se apoya en el índice parcial {@code stock_reservation_pending_idx}, que solo cubre las
     * filas en HELD. En una tabla con millones de reservas históricas, esta consulta mira
     * únicamente el puñado que sigue pendiente.
     */
    @Override
    public List<Reservation> findExpired(Instant now, int limit) {
        return entityManager.createQuery("""
                        select r from ReservationEntity r
                         where r.status = :held and r.expiresAt <= :now
                         order by r.expiresAt
                        """, ReservationEntity.class)
                .setParameter("held", ReservationStatus.HELD)
                .setParameter("now", now)
                .setMaxResults(limit)
                .getResultList()
                .stream()
                .map(ReservationEntity::toDomain)
                .toList();
    }
}
