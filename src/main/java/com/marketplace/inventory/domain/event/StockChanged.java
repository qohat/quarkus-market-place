package com.marketplace.inventory.domain.event;

import com.marketplace.inventory.domain.StockItem;
import com.marketplace.shared.outbox.DomainEvent;

/**
 * Las existencias de una publicación cambiaron.
 *
 * <p>Es el evento que cierra el cabo suelto del módulo 6: el catálogo guarda una copia de
 * {@code availableStock} para el escaparate, y hasta ahora nadie la actualizaba. Esto es lo que
 * la sincroniza.
 *
 * <h2>Por qué lleva el estado y no el cambio</h2>
 *
 * Podría decir «se reservaron 3 unidades» en lugar de «quedan 7 disponibles». Llevar el
 * <strong>estado resultante</strong> tiene una ventaja decisiva: <strong>aplicar el mensaje dos
 * veces da el mismo resultado</strong>. Con un delta, procesar un duplicado descontaría tres
 * unidades de más.
 *
 * <p>Como el outbox garantiza at-least-once, los duplicados van a ocurrir. Un evento que lleva
 * estado es idempotente por construcción, sin necesidad de recordar qué mensajes ya se vieron.
 * Es la forma más barata de sobrevivir a una entrega repetida.
 *
 * <p>El nombre va en pasado porque describe algo que <em>ya ocurrió</em>. Un evento no es una
 * orden: quien lo recibe no puede negarse.
 */
public record StockChanged(String listingId, int onHand, int reserved, int available)
        implements DomainEvent {

    public static StockChanged from(StockItem item) {
        return new StockChanged(
                item.listingId().toString(), item.onHand(), item.reserved(), item.available());
    }

    @Override
    public String aggregateType() {
        return "stock";
    }

    @Override
    public String aggregateId() {
        return listingId;
    }
}
