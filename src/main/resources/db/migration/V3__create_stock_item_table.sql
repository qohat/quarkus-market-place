-- Existencias: el contexto de Inventario.
--
-- Tabla propia, y no columnas añadidas a `listing`, porque son dos bounded contexts: el
-- catálogo modela algo que se muestra y el inventario un contador que se agota. Separarlas
-- deja dibujada la costura por la que este contexto podrá extraerse a un servicio propio.
--
-- POR QUÉ NO HAY CLAVE FORÁNEA A `listing`
--
-- Sería fácil de añadir y daría integridad referencial gratis, pero acopla los dos contextos
-- a nivel de esquema: el día que Inventario tenga su propia base de datos, esa restricción no
-- puede existir. Renunciar a ella ahora mantiene honesta la frontera y obliga a tratar la
-- ausencia de existencias como un caso de negocio (StockItemNotFoundException) en vez de como
-- un error de integridad.
--
-- Los servicios reservables no aparecen aquí: su recurso escaso es el tiempo, no las unidades,
-- y se resuelve con solapamiento de intervalos, no con un contador.

CREATE TABLE stock_item (
    listing_id UUID PRIMARY KEY,

    -- Unidades que existen físicamente.
    on_hand INTEGER NOT NULL,

    -- Unidades apartadas para compras en curso, todavía sin cobrar. Lo vendible es la resta:
    -- on_hand - reserved. Hacen falta las dos porque comprar no es instantáneo.
    reserved INTEGER NOT NULL DEFAULT 0,

    -- Para el bloqueo optimista. Las tres estrategias comparten tabla, así que esta columna
    -- existe siempre aunque solo la use una de ellas.
    version BIGINT NOT NULL DEFAULT 0,

    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT stock_item_non_negative CHECK (on_hand >= 0 AND reserved >= 0),

    -- LA ÚLTIMA LÍNEA DE DEFENSA CONTRA LA SOBREVENTA.
    --
    -- El mismo invariante que comprueba el constructor de StockItem, escrito también aquí. No
    -- es duplicación por descuido: es que este es el único punto por el que pasan de verdad
    -- TODAS las escrituras, incluidas las de un UPDATE directo, un script de migración de datos
    -- o una consola de soporte a las tres de la mañana.
    --
    -- Si alguna vez una condición de carrera se cuela por el código, aquí la petición muere con
    -- un error de restricción en vez de vender algo que no existe. Un 500 es mucho mejor
    -- resultado que una venta imposible de servir.
    CONSTRAINT stock_item_reserved_within_on_hand CHECK (reserved <= on_hand)
);

-- Para el proceso que caduca reservas abandonadas: busca por antigüedad entre las que tienen
-- algo reservado. El índice parcial deja fuera la inmensa mayoría de filas, que no tienen
-- ninguna reserva en curso.
CREATE INDEX stock_item_with_reservations_idx
    ON stock_item (updated_at)
    WHERE reserved > 0;
