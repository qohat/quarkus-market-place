-- La bandeja de salida: el patrón outbox.
--
-- EL PROBLEMA QUE RESUELVE
--
-- Confirmar una venta exige dos escrituras: actualizar el inventario en PostgreSQL y publicar
-- un evento en Kafka. No hay forma de hacerlas atómicas, porque @Transactional solo cubre la
-- base de datos:
--
--   la BD confirma y Kafka falla   →  evento perdido, el catálogo miente para siempre
--   Kafka publica y la BD revierte →  evento fantasma de algo que no ocurrió
--
-- Existe el compromiso en dos fases (2PC/XA), que sí las haría atómicas. Nadie lo usa con
-- Kafka: es lento, exige que todos los participantes lo soporten, y si el coordinador cae en
-- el momento equivocado deja recursos bloqueados hasta que alguien intervenga a mano.
--
-- LA SOLUCIÓN
--
-- Si no puedes hacer atómicas dos escrituras a sistemas distintos, haz que sean dos escrituras
-- al MISMO sistema. El evento se guarda aquí, en la misma transacción que el cambio de negocio:
-- o se guardan los dos, o ninguno. Un proceso aparte lo lleva después a Kafka.

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,

    -- Qué clase de cosa cambió ('stock', 'reservation'…). Sirve para enrutar a un tema u otro
    -- sin tener que mirar dentro del payload.
    aggregate_type VARCHAR(64) NOT NULL,

    -- EL IDENTIFICADOR DEL AGREGADO, Y LA CLAVE DE PARTICIÓN EN KAFKA.
    --
    -- Kafka solo garantiza el orden DENTRO de una partición, no globalmente. Si los eventos de
    -- una misma publicación cayeran en particiones distintas, el consumidor podría procesar
    -- «stock = 7» antes que «stock = 9» y dejar el catálogo mintiendo de forma permanente.
    --
    -- Usando esta columna como clave, todos los eventos de un mismo agregado van siempre a la
    -- misma partición y llegan en el orden en que ocurrieron.
    aggregate_id UUID NOT NULL,

    event_type VARCHAR(64) NOT NULL,

    -- JSONB y no TEXT: permite consultar dentro del payload cuando haya que investigar un
    -- incidente, que es exactamente cuando no se puede desplegar código nuevo para mirarlo.
    payload JSONB NOT NULL,

    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- NULL = pendiente de publicar. Se conserva la fila después de publicarla en lugar de
    -- borrarla: es el registro de qué se emitió y cuándo, impagable al depurar. Una tarea de
    -- limpieza puede purgar lo antiguo.
    published_at TIMESTAMPTZ
);

-- Índice PARCIAL sobre lo pendiente. La tabla crecerá hasta millones de filas publicadas, pero
-- este índice solo contiene las que quedan por publicar: normalmente unas pocas, o ninguna.
-- El relay consulta contra un índice diminuto por muy grande que sea la tabla.
CREATE INDEX outbox_pending_idx
    ON outbox_event (occurred_at)
    WHERE published_at IS NULL;
