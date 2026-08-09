-- Reservas con caducidad.
--
-- El contador de `stock_item` dice CUÁNTAS unidades hay apartadas. Esta tabla dice QUIÉN las
-- tiene apartadas y HASTA CUÁNDO, que es lo que permite devolverlas cuando alguien abandona el
-- carrito. Sin ella, `reserved` solo puede crecer.
--
-- Es el único diseño que resuelve el problema real: comprar no es instantáneo.
--
--   descontar al pagar     →  el comprador se queda sin stock DESPUÉS de pagar
--   descontar al empezar   →  un carrito abandonado bloquea inventario para siempre
--   reservar con caducidad →  ninguna de las dos cosas

CREATE TABLE stock_reservation (
    id UUID PRIMARY KEY,
    listing_id UUID NOT NULL,
    buyer_id UUID NOT NULL,
    units INTEGER NOT NULL,

    -- HELD      apartadas, esperando pago
    -- CONFIRMED pagadas: las unidades salieron del almacén
    -- RELEASED  devueltas al inventario (cancelación o caducidad)
    status VARCHAR(16) NOT NULL,

    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT stock_reservation_units_positive CHECK (units > 0),
    CONSTRAINT stock_reservation_status_valid
        CHECK (status IN ('HELD', 'CONFIRMED', 'RELEASED'))
);

-- Índice para el proceso de barrido, que busca reservas vencidas y todavía retenidas.
--
-- Es PARCIAL a propósito: en un marketplace con millones de reservas históricas, solo un puñado
-- están en HELD en un momento dado. El índice ocupa lo que ocupan esas pocas, en lugar de
-- indexar toda la historia para no volver a mirarla jamás.
CREATE INDEX stock_reservation_pending_idx
    ON stock_reservation (expires_at)
    WHERE status = 'HELD';

-- Para consultar las reservas de un comprador.
CREATE INDEX stock_reservation_buyer_idx ON stock_reservation (buyer_id, created_at DESC);
