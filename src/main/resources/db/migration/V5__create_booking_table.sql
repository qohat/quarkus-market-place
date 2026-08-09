-- Reservas de servicios: el OTRO problema de concurrencia.
--
-- Un producto se agota con un contador: stock - 1. Un servicio no. Dos personas pueden reservar
-- la misma clase de guitarra el martes, pero no las dos de 10:00 a 11:00. El recurso escaso no
-- es una cantidad, es un INTERVALO, y lo que hay que impedir es el SOLAPAMIENTO.
--
-- Esa dualidad es la razón de que este marketplace sea híbrido desde el módulo 1.
--
-- ¿POR QUÉ NO RESOLVERLO EN JAVA?
--
-- La forma evidente sería consultar si hay reservas que solapen y, si no las hay, insertar:
--
--     A: consulta 10:00-11:00 → libre ─┐
--     B: consulta 10:00-11:00 → libre ─┤  las dos creen que pueden
--     A: inserta                       ─┤
--     B: inserta                       ─┘  doble reserva
--
-- Es exactamente la sobreventa, con otro disfraz. Y aquí no vale el truco del UPDATE atómico,
-- porque no hay una fila que actualizar: hay que insertar una nueva comprobando algo sobre las
-- que ya existen.

-- GiST no sabe indexar la igualdad de tipos escalares como UUID. Esta extensión se la enseña,
-- que es lo que permite combinar `listing_id WITH =` y `slot WITH &&` en la misma restricción.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE booking (
    id UUID PRIMARY KEY,
    listing_id UUID NOT NULL,
    buyer_id UUID NOT NULL,

    -- Un intervalo con zona horaria, como un solo valor. Guardar inicio y fin en dos columnas
    -- obligaría a escribir a mano la lógica de solapamiento —que es más sutil de lo que
    -- parece— en cada consulta. Como rango, el operador && la resuelve.
    slot TSTZRANGE NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- LA RESTRICCIÓN QUE HACE IMPOSIBLE LA DOBLE RESERVA.
    --
    -- Se lee: «no pueden existir dos filas donde el listing_id sea IGUAL (=) y los intervalos
    -- se SOLAPEN (&&)».
    --
    -- La comprobación la hace PostgreSQL dentro de la propia inserción, con el mismo nivel de
    -- garantía que una clave única. No hay ventana entre consultar e insertar porque no hay
    -- consulta: dos transacciones simultáneas pidiendo la misma franja se serializan solas, y
    -- la segunda recibe una violación de restricción.
    --
    -- Fíjate en lo que esto significa: la regla de negocio «no solapar» queda garantizada aunque
    -- el código Java esté mal escrito, aunque alguien inserte a mano desde psql, y aunque haya
    -- veinte instancias de la aplicación desplegadas. Ningún candado en Java da eso.
    CONSTRAINT booking_no_overlap
        EXCLUDE USING gist (listing_id WITH =, slot WITH &&)
);

CREATE INDEX booking_buyer_idx ON booking (buyer_id, created_at DESC);
