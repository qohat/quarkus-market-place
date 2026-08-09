# Módulo 6 — Bounded contexts e inventario

Dos mitades. La primera parece filosófica hasta que la sufres; la segunda es el problema de
concurrencia más caro que tiene un marketplace: **dos compradores, una unidad**.

Todos los números de este documento salieron de ejecuciones reales contra PostgreSQL.

---

# 1 · Bounded contexts

Un **bounded context** (Evans, *DDD*) es un límite dentro del cual un modelo tiene un significado
único. La idea incómoda:

> **La misma palabra significa cosas distintas en sitios distintos, y eso no es un problema a
> resolver: es la realidad a modelar.**

| Contexto | Qué es un *listing* | Qué le importa |
|---|---|---|
| **Catálogo** | Algo que se muestra | Título, precio, fotos, estado de publicación |
| **Inventario** | Un contador | Cuántas unidades quedan, cuántas hay apartadas |
| **Pedidos** | Una línea de compra | Precio **congelado**, cantidad, envío |

Fíjate en «precio **congelado**»: si el vendedor sube el precio mañana, tu pedido de ayer no
cambia. En Catálogo el precio es un dato vivo; en Pedidos, histórico. **Son cosas distintas que se
llaman igual.**

La tentación es una clase `Listing` que sirva para todo. Acabas con treinta campos donde cada uno
solo importa a una parte, y `null` por todas partes: el **modelo canónico**, que falla siempre.

## La razón de alta escala

Los contextos son la **unidad de consistencia**:

```
DENTRO de un contexto  →  transacción ACID, todo o nada
ENTRE contextos        →  consistencia eventual, mensajes, compensaciones
```

Esa línea decide dónde cabe un `@Transactional` y dónde harán falta el patrón outbox y una saga
—módulo 7—. **Los contextos que se dibujan aquí son las costuras por las que el sistema se parte
cuando crece.**

## Dónde va exactamente la frontera

El catálogo ya tenía `ProductListing.availableStock`. Si Inventario pasa a ser el dueño del
stock, ¿se quita?

La opción purista —quitarlo y preguntar a Inventario— tiene un problema serio:

```
GET /listings   →  20 publicaciones por página
                →  ¿20 consultas a Inventario para pintar «quedan 3»?
```

Es el N+1 del módulo 3 **cruzando una frontera de contexto**, que mañana puede ser una llamada de
red. Es el error clásico al descomponer un monolito: dibujar bien la frontera y luego atravesarla
mil veces por petición.

Lo que se hizo, y lo que hacen los marketplaces reales:

```
Inventario  →  LA VERDAD.  Aquí se reserva y se decide si hay stock
Catálogo    →  UNA COPIA.  Un número para el escaparate, que puede ir atrasado
```

> **Puedes leer una copia atrasada para mostrar, nunca para decidir.** Enseñar «quedan 3» con
> datos de hace dos segundos es aceptable —para cuando lleguen a la pantalla ya estarán viejos de
> todos modos—. Vender con ellos, no.

Lo que sincroniza la copia con la verdad es un evento, y eso es el módulo 7.

## Qué se comparte y qué no

`StockItem` importa `ListingId` del catálogo. Compartir el **identificador** es deliberado: es el
punto de encuentro entre contextos, y darle a Inventario un id propio obligaría a mantener una
tabla de correspondencias sin ganar nada.

Lo que **no** se comparte es el modelo: en Inventario no entra `Listing`, ni `Money`, ni el estado
de publicación. **La frontera protege los conceptos, no los números.**

Tampoco hay clave foránea entre `stock_item` y `listing`. Sería fácil y daría integridad gratis,
pero acopla los contextos a nivel de esquema: el día que Inventario tenga su propia base de datos
no puede existir. Renunciar a ella mantiene honesta la frontera.

---

# 2 · El modelo: por qué dos contadores

```
onHand    = 10    unidades que existen físicamente
reserved  =  3    apartadas para compras en curso, sin cobrar
available =  7    ← lo único vendible
```

Hacen falta dos porque **comprar no es instantáneo**:

```
descontar al pagar     →  se queda sin stock DESPUÉS de haber pagado
descontar al empezar   →  un carrito abandonado bloquea inventario para siempre
reservar con caducidad →  ninguna de las dos cosas
```

Y de ahí el detalle más contraintuitivo del modelo, fijado en un test:

```java
assertEquals(7, reservado.available());
assertEquals(7, confirmado.available(), "confirmar no vende nada nuevo");
```

**Confirmar el pago no cambia lo disponible.** Ya se descontó al reservar; confirmar solo hace
definitivo lo apartado. Si `available()` bajara al confirmar, se descontaría dos veces.

## El puerto declara intención, no mecanismo

```java
StockItem reserve(ListingId listingId, int units);   // ✓
// en lugar de:  find(...) + save(...)               // ✗
```

Parece un matiz y es la decisión clave. Un puerto con `find` + `save` **obliga** a leer antes de
escribir, y esa ventana *es* la sobreventa:

```
A lee stock=1 ──┐
B lee stock=1 ──┤  los dos creen que pueden vender
A escribe 0   ──┤
B escribe 0   ──┘  dos ventas, una unidad
```

Declarando «reserva dos unidades», cada adaptador lo resuelve como quiera — y por eso las tres
estrategias son intercambiables sin tocar una línea de negocio.

---

# 3 · Las tres estrategias, medidas

Tres adaptadores del mismo puerto, seleccionables con
`marketplace.inventory.strategy`. `@Typed` los deja inyectables solo por su clase concreta —si los
tres se ofrecieran como `StockRepository`, ARC fallaría el build por dependencia ambigua— y
`StockRepositoryProducer` elige el activo.

## 1 unidad · 200 compradores simultáneos

| Estrategia | Ventas | Rechazos | Tiempo |
|---|---:|---:|---:|
| atomic | 1 | 199 | 859 ms |
| optimistic | 1 | 199 | 114 ms |
| pessimistic | 1 | 199 | 86 ms |

**Ninguna sobrevende.** Las tres son correctas. Lo que cambia es el coste de decir que no:

```
optimista   199 rechazados tras hacer el trabajo entero y tirarlo
pesimista   199 esperando en cola, cada uno reteniendo una conexión
atómico     199 UPDATE que afectan a 0 filas y terminan
```

## 50 unidades · 200 compradores — aquí se rompe el empate

| Estrategia | Ventas de 50 posibles |
|---|---:|
| atomic | **50** |
| pessimistic | **50** |
| optimistic | **13** ← 37 unidades sin vender |

**El bloqueo optimista perdió 37 ventas teniendo stock en el almacén.** No es lentitud: es dinero
que no entra, con compradores esperando y mercancía disponible.

Y la causa es más sutil de lo que parece: dos compradores que reservan unidades **distintas** —hay
50— escriben igualmente **la misma fila**, así que chocan por la versión.

> **El conflicto es de fila, no de negocio.** La contención no la crea la escasez: la crea
> compartir un contador.

Con reintentos se recuperaría parte, a cambio de multiplicar la carga justo cuando el sistema está
más ocupado, que es la definición de un fallo en cascada.

## Y el hallazgo que no esperaba: fuga de inventario

Test de reservar-y-soltar, 100 carritos abandonados:

```
atomic        quedan 0 reservadas de 100
pessimistic   quedan 0 reservadas de 100
optimistic    quedan 4 reservadas de 100   ← apartadas PARA SIEMPRE
```

Cuando el `release` choca por versión, la reserva se queda hecha. Cuatro unidades que existen,
nadie va a comprar y nadie va a liberar. **El inventario se evapora poco a poco**, y nada en los
logs lo dice.

## Un dato incómodo sobre nuestra implementación

Los 859 ms de `atomic` con 1 unidad, contra 86 del pesimista, **no son la estrategia: son cómo se
escribió**. En el camino de fallo hay una lectura extra para poder decir *cuántas* unidades
quedaban:

```java
if (execute(RESERVE, listingId, units) == 0) {
    var actual = find(listingId)...        // ← 199 consultas de más
    throw new InsufficientStockException(listingId, units, actual.available());
}
```

Con 50 unidades, donde hay menos rechazos, desaparece: **82 ms, igual que el pesimista**. Se deja
así —un error que dice «quedan 2, ¿te sirven?» vale más que 700 ms en un caso patológico— pero
conviene saber que está ahí.

## El precio de la estrategia atómica

Las dos primeras aplican la regla llamando a `StockItem.reserve(int)`: la lógica vive en el
dominio, en un solo sitio. La tercera la escribe en un `WHERE`, así que **la misma regla queda en
dos sitios**, Java y SQL, y nada garantiza que sigan de acuerdo dentro de un año.

Es una duplicación consciente. El precio se paga con `StockConcurrencyTest`, que ejecuta la misma
batería contra las tres: si dejan de comportarse igual, el build lo dice.

Y no es la respuesta a todo: funciona porque reservar es aritmética que cabe en una sentencia. Una
regla que dependa de leer varias filas, o de consultar otro servicio, no se puede expresar así.

## La última línea de defensa

```sql
CONSTRAINT stock_item_reserved_within_on_hand CHECK (reserved <= on_hand)
```

El mismo invariante que comprueba el constructor de `StockItem`, escrito también en la tabla. No
es duplicación por descuido: **la base de datos es el único punto por el que pasan de verdad todas
las escrituras**, incluidas las de un script de migración o una consola de soporte a las tres de
la mañana.

Si una condición de carrera se cuela por el código, la petición muere ahí con un error de
restricción. Un 500 es mucho mejor resultado que una venta imposible de servir.

---

# 4 · Reservas con caducidad

`stock_item` dice **cuántas** unidades están apartadas; `stock_reservation` dice **quién** las
tiene y **hasta cuándo**. Sin la segunda, el contador solo puede crecer.

El TTL es configurable (`marketplace.inventory.reservation-ttl`, 15 min por defecto) porque es una
decisión de negocio: corto libera antes el inventario pero echa a quien esté tecleando la tarjeta;
largo lo deja bloqueado.

## Idempotencia por diseño del estado

Con varias réplicas desplegadas, dos barridos pueden coger la misma reserva vencida. La protección
no es un candado distribuido:

```java
private void requireHeld(String operation) {
    if (status != ReservationStatus.HELD) {
        throw new IllegalStateException("cannot " + operation + " a reservation in status " + status);
    }
}
```

Solo se sale de `HELD`, y solo una vez. El segundo barrido encuentra `RELEASED`, lanza, y las
unidades no se devuelven dos veces. **Idempotencia por diseño del estado en lugar de por
coordinación entre instancias** — el mismo principio que sostendrá el módulo 7 frente a mensajes
duplicados.

El barrido procesa en lotes de 500: si se acumulan cien mil vencidas, una transacción gigante
bloquearía filas y castigaría al resto. La siguiente ejecución sigue por donde se quedó.

Y el instante entra como parámetro (`releaseExpired(Instant now)`) en vez de leerse de
`Instant.now()`: así el paso del tiempo es un dato de entrada y la caducidad se prueba sin esperar
de verdad.

---

# 5 · Franjas horarias: el otro problema de concurrencia

Un producto se agota con un contador. Un servicio no: dos personas pueden reservar la misma clase
el martes, pero no de 10:00 a 11:00 las dos. **El recurso escaso es un intervalo, y lo que hay que
impedir es el solapamiento.**

Esa dualidad es la razón de que este marketplace sea híbrido desde el módulo 1.

Aquí **no vale el truco del UPDATE atómico**: no hay una fila que actualizar, hay que *insertar*
comprobando algo sobre las que ya existen. Y hacerlo en Java es la sobreventa otra vez:

```
A: consulta 10:00-11:00 → libre ─┐
B: consulta 10:00-11:00 → libre ─┤  las dos creen que pueden
A: inserta                       ─┤
B: inserta                       ─┘  doble reserva
```

## La respuesta de PostgreSQL

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE booking (
    id UUID PRIMARY KEY,
    listing_id UUID NOT NULL,
    buyer_id UUID NOT NULL,
    slot TSTZRANGE NOT NULL,
    CONSTRAINT booking_no_overlap
        EXCLUDE USING gist (listing_id WITH =, slot WITH &&)
);
```

Se lee: **«no pueden existir dos filas donde el `listing_id` sea igual y los intervalos se
solapen»**. La comprobación ocurre dentro de la propia inserción, con la misma garantía que una
clave única.

Resultado medido con **100 compradores simultáneos por la misma hora: 1 reserva, 99 rechazos.** Sin
consultar antes, sin bloquear nada y sin una línea de coordinación en Java.

Y el alcance de esa garantía es lo notable: **se mantiene aunque el código Java esté mal escrito,
aunque alguien inserte a mano desde `psql` y aunque haya veinte instancias desplegadas.** Ningún
candado en Java llega tan lejos.

Detalles que importan:

- **`btree_gist`**: GiST no sabe indexar la igualdad de tipos escalares como UUID. La extensión se
  la enseña, y es lo que permite combinar `listing_id WITH =` con `slot WITH &&`.
- **`tstzrange(inicio, fin, '[)')`** — cerrado por la izquierda, abierto por la derecha. Es lo que
  hace que 10:00–11:00 y 11:00–12:00 **no** se consideren solapadas: con el extremo derecho
  cerrado, un profesor no podría encadenar dos clases seguidas.
- **`listing_id WITH =`** acota el solapamiento a cada publicación. Sin él, una reserva bloquearía
  esa hora en todo el marketplace.

---

# 6 · Lo que enseñaron los fallos

| Fallo | Lección |
|---|---|
| `@MethodSource` debe ser estático | Salvo con `@TestInstance(PER_CLASS)`, imprescindible cuando la fábrica necesita beans inyectados |
| El optimista vendió 13 de 50 | **El conflicto es de fila, no de negocio.** Correcto ≠ aprovechado |
| 4 unidades apartadas para siempre | Un `release` que choca deja fuga de inventario, y nada lo registra |
| `StockItemNotFoundException` en el barrido | El `DatabaseCleaner` no borraba las tablas nuevas. **Un cleaner incompleto no falla donde se olvidó, sino en el siguiente test que corra** |
| `ConstraintViolationException` sin traducir | `getConstraintName()` llega **null** en las restricciones de exclusión de PostgreSQL. Hay que mirar también el mensaje |
| Test intermitente de firma manipulada | En base64, los caracteres finales codifican bits de relleno que se descartan: cambiar el último a veces **no cambia nada** |
| `fromDomain is not public` | El compilador señaló un error de **arquitectura**, no de visibilidad: la capa de aplicación estaba tocando entidades JPA. De ahí nació `ReservationRepository` |

---

# 7 · Cómo probarlo

```bash
sdk env
./gradlew test --tests "*StockConcurrencyTest*"     # las tres estrategias, cara a cara
./gradlew test --tests "*BookingConcurrencyTest*"   # 100 compradores por la misma hora
./gradlew test --tests "*InventoryTest*"            # ciclo de compra y caducidad
```

Para cambiar la estrategia activa de la aplicación:

```properties
marketplace.inventory.strategy=optimistic    # atomic (por defecto) | optimistic | pessimistic
marketplace.inventory.reservation-ttl=PT5M   # duración de una reserva sin pagar
```

## Cosas para probar por tu cuenta

| Prueba | Qué esperar |
|---|---|
| Subir `COMPRADORES` a 1000 en `StockConcurrencyTest` | El optimista pierde proporcionalmente más ventas |
| Quitar el `CHECK (reserved <= on_hand)` y forzar una carrera | La base de datos deja de ser la red de seguridad |
| Cambiar `'[)'` por `'[]'` en la migración de bookings | Dos citas consecutivas dejan de caber |
| Quitar `listing_id WITH =` de la restricción | Una reserva bloquea esa hora en todo el marketplace |
| Añadir reintentos al optimista y volver a medir | Recupera ventas, a costa de multiplicar la carga |

## Los mandamientos

1. **La misma palabra significa cosas distintas en contextos distintos.** No fuerces un modelo
   único.
2. **Lee una copia atrasada para mostrar, nunca para decidir.**
3. **Comparte identificadores entre contextos, nunca modelos.**
4. **Un puerto declara intención, no mecanismo**, o cierra la puerta a la mejor implementación.
5. **Correcto no es suficiente**: el optimista no sobrevende y aun así pierde ventas.
6. **El conflicto es de fila, no de negocio.** Compartir un contador basta para crear contención.
7. **Pon el invariante también en la base de datos.** Es el único sitio por el que pasan todas las
   escrituras.
8. **Idempotencia por diseño del estado**, no por coordinación entre instancias.
9. **Delega en la base de datos lo que sabe hacer mejor.** `EXCLUDE USING gist` da una garantía que
   ningún candado en Java alcanza.
