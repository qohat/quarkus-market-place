-- =============================================================================
-- V1 — Tabla de publicaciones del catálogo
--
-- Estrategia SINGLE_TABLE: productos y servicios comparten tabla, distinguidos
-- por la columna discriminadora listing_type.
--
-- El motivo es de rendimiento y es el correcto para este dominio: la consulta
-- del catálogo es el 95% del tráfico de lectura, y aquí no hace NINGÚN join.
-- Con la alternativa JOINED, cada listado del catálogo pagaría dos LEFT JOIN.
--
-- El coste es que las columnas específicas de cada subtipo tienen que admitir
-- NULL, así que el esquema por sí solo no impide un producto sin stock. Ese
-- hueco se tapa con CHECK constraints condicionales, más abajo.
-- =============================================================================

CREATE TABLE listing (
    id             UUID          PRIMARY KEY,
    listing_type   VARCHAR(16)   NOT NULL,
    seller_id      UUID          NOT NULL,
    title          VARCHAR(200)  NOT NULL,

    -- Money se descompone en importe + moneda. NUNCA una columna sola: un número
    -- sin moneda no es dinero, y en cuanto entre una segunda divisa el sistema
    -- empezaría a sumar peras con manzanas sin que nada falle.
    --
    -- NUMERIC, no DOUBLE PRECISION: NUMERIC es decimal exacto en PostgreSQL,
    -- mientras que DOUBLE es binario IEEE-754 y no puede representar 0.10 con
    -- exactitud. Escala 4 (y no 2) para admitir monedas de tres decimales, como
    -- el dinar bahreiní, sin necesidad de migrar la tabla.
    -- VARCHAR(3) y no CHAR(3): en PostgreSQL, CHAR es «bpchar» (blank-padded) y rellena
    -- con espacios hasta la longitud fija, de modo que 'EUR' se guardaría como 'EUR' pero
    -- cualquier código más corto arrastraría espacios invisibles en las comparaciones.
    -- Además no ahorra un solo byte frente a VARCHAR. CHAR casi nunca es la opción correcta
    -- en PostgreSQL.
    price_amount   NUMERIC(19,4) NOT NULL,
    price_currency VARCHAR(3)    NOT NULL,

    status         VARCHAR(16)   NOT NULL,

    -- Solo PRODUCT
    available_stock INTEGER,

    -- Solo SERVICE
    slot_minutes    INTEGER,
    time_zone       VARCHAR(64),
    max_bookings    INTEGER,

    -- TIMESTAMPTZ, no TIMESTAMP: guarda el instante absoluto y no depende de la
    -- zona horaria del servidor, que es distinta en tu portátil y en el clúster.
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- -------------------------------------------------------------------------
    -- Invariantes en la base de datos.
    --
    -- Sí, el dominio ya los valida. Se repiten aquí a propósito: la aplicación no
    -- es el único camino hacia estos datos. Un script de migración, un job de
    -- backfill, un becario con psql o un bug futuro pueden escribir directamente.
    -- La base de datos es la última línea de defensa, y la única que nadie puede
    -- saltarse.
    --
    -- Ojo: esto NO contradice la regla de no duplicar reglas de negocio en los
    -- DTOs. Un DTO es un camino de entrada más; la base de datos es el custodio
    -- final del estado.
    -- -------------------------------------------------------------------------

    CONSTRAINT listing_type_valid
        CHECK (listing_type IN ('PRODUCT', 'SERVICE')),

    CONSTRAINT listing_status_valid
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'PAUSED', 'ARCHIVED')),

    CONSTRAINT listing_title_not_blank
        CHECK (length(btrim(title)) > 0),

    CONSTRAINT listing_price_positive
        CHECK (price_amount > 0),

    CONSTRAINT listing_currency_format
        CHECK (price_currency ~ '^[A-Z]{3}$'),

    -- Un producto tiene stock y solo stock: nada de campos de calendario.
    CONSTRAINT listing_product_fields
        CHECK (listing_type <> 'PRODUCT' OR (
            available_stock IS NOT NULL AND available_stock >= 0
            AND slot_minutes IS NULL
            AND time_zone    IS NULL
            AND max_bookings IS NULL
        )),

    -- Un servicio tiene calendario y solo calendario: nada de stock.
    CONSTRAINT listing_service_fields
        CHECK (listing_type <> 'SERVICE' OR (
            slot_minutes IS NOT NULL AND slot_minutes > 0 AND slot_minutes <= 1440
            AND time_zone    IS NOT NULL
            AND max_bookings IS NOT NULL AND max_bookings >= 1
            AND available_stock IS NULL
        ))
);

-- -----------------------------------------------------------------------------
-- Índices
-- -----------------------------------------------------------------------------

-- Índice PARCIAL: solo indexa las filas visibles en el catálogo público.
--
-- Es una función de PostgreSQL que merece conocer. En un marketplace maduro, la
-- inmensa mayoría de las publicaciones acaban archivadas, así que un índice sobre
-- toda la columna status desperdiciaría espacio indexando filas que esa consulta
-- no mira jamás. Este índice se mantiene pequeño y cabe en memoria.
CREATE INDEX listing_visible_idx
    ON listing (status)
    WHERE status IN ('PUBLISHED', 'PAUSED');

-- Panel del vendedor: sus publicaciones, ordenadas por título.
CREATE INDEX listing_seller_idx
    ON listing (seller_id, title);
