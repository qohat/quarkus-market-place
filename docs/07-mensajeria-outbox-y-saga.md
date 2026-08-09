# Módulo 7 — Mensajería: Kafka, outbox y saga

El módulo que recoge los dos cabos que el 6 dejó sueltos a propósito:

```
1. El catálogo tiene una copia de availableStock que NADIE sincronizaba
2. La frontera entre contextos ya no se puede cruzar con un @Transactional
```

---

# 1 · El problema: la escritura dual

Confirmar una venta exige dos escrituras a sistemas distintos:

```java
@Transactional
public void confirm(ReservationId id) {
    inventory.confirm(id);                      // PostgreSQL
    kafka.send(new StockChanged(listingId, 7)); // Kafka
}
```

Parece razonable y **está roto de las dos maneras posibles**:

```
la BD confirma y Kafka falla   →  evento perdido. El catálogo miente para siempre
Kafka publica y la BD revierte →  evento fantasma de algo que no ocurrió
```

No hay arreglo dentro de ese método: **`@Transactional` solo cubre la base de datos.**

> Existe el compromiso en dos fases (**2PC/XA**), que sí haría atómicas ambas escrituras. Nadie lo
> usa con Kafka: es lento, exige que todos los participantes lo soporten, y si el coordinador cae
> en el momento equivocado deja recursos bloqueados hasta que alguien intervenga a mano. La
> industria lo abandonó.

---

# 2 · El patrón outbox

La idea es de una simplicidad tramposa: **si no puedes hacer atómicas dos escrituras a sistemas
distintos, haz que sean dos escrituras al mismo sistema.**

```
┌─ transacción de PostgreSQL ────────────────┐
│  UPDATE stock_item  SET reserved = ...      │
│  INSERT INTO outbox_event (...)             │   ← el evento, como una fila más
└─────────────────── commit atómico ─────────┘
                    ↓
   relay:  SELECT FROM outbox → publica en Kafka → marca como publicado
```

Consecuencia práctica: quien llama a `outbox.publish(evento)` **no está publicando**, está
prometiendo publicar. El evento sale cuando el relay lo recoja, un segundo después. Ese retardo es
el precio, y es aceptable porque a cambio la entrega deja de poder perderse.

## Dos decisiones de la tabla

**El `aggregate_id` es la clave de partición en Kafka.** Kafka solo garantiza el orden **dentro de
una partición**. Si los eventos de una misma publicación cayeran en particiones distintas, el
consumidor podría aplicar «stock = 7» después de «stock = 9» y dejar el catálogo mintiendo de
forma permanente. Con el id del agregado como clave, todos van a la misma partición.

**El índice es parcial.** La tabla crecerá a millones de filas publicadas, pero
`WHERE published_at IS NULL` solo indexa lo pendiente: normalmente unas pocas, o ninguna. El relay
consulta contra un índice diminuto por muy grande que sea la tabla.

Y no se borran las filas publicadas: son el registro de qué se emitió y cuándo, impagable al
depurar.

---

# 3 · Por qué el outbox duplica, y por qué no se puede evitar

```
1. lee el evento de la tabla
2. lo publica en Kafka        ✓
3. lo marca como publicado    ✗ ← se cae aquí
```

Al reiniciar, vuelve a leerlo y **lo publica otra vez**. Invertir 2 y 3 no arregla nada: cambia
duplicar por perder. Son las dos únicas opciones:

| Garantía | Qué significa |
|---|---|
| **at-most-once** | Puede perderse. Nunca duplica |
| **at-least-once** | Nunca se pierde. Puede duplicar ← *lo que da el outbox* |
| **exactly-once** | …no existe de extremo a extremo |

**«Exactly-once» es la mentira más repetida de los sistemas distribuidos.** Kafka tiene una función
con ese nombre y es real, pero solo cubre Kafka-a-Kafka dentro de su propio mundo; en cuanto
interviene tu base de datos, tu pasarela de pago o un correo electrónico, se acabó.

Lo que sí se consigue:

```
at-least-once  +  consumidor idempotente  =  effectively once
```

## Las tres formas de ser idempotente, de más barata a más cara

**1 · Por la forma del mensaje** — la que usamos aquí:

```java
public record StockChanged(String listingId, int onHand, int reserved, int available)
```

Lleva el **estado resultante**, no el cambio. «Quedan 7 disponibles» en vez de «se reservaron 3».
Aplicarlo dos veces deja el mismo número; con un delta, el duplicado descontaría tres unidades de
más. **No hace falta recordar nada.**

**2 · Por diseño del estado** — la del módulo 6:

```java
if (status != ReservationStatus.HELD) throw new IllegalStateException(...);
```

La operación es segura de repetir porque el estado no deja repetirla.

**3 · Por registro de mensajes vistos** (tabla *inbox*) — la más general y la más cara. Solo cuando
las dos anteriores no son posibles.

---

# 4 · El relay y `SKIP LOCKED`

Con tres réplicas desplegadas, las tres sondean la misma tabla. Sin protección, publicarían todo
por triplicado.

```sql
select * from outbox_event
 where published_at is null
 order by occurred_at
 limit ?
   for update skip locked
```

En el módulo 6, `FOR UPDATE` hacía **esperar** al segundo. `SKIP LOCKED` dice lo contrario: *«no
esperes — sáltate lo que otro tiene cogido y llévate lo siguiente»*. Las tres réplicas se reparten
el trabajo solas, **sin coordinador y sin duplicar**.

Es el mismo mecanismo con el que se construye una cola de trabajo sobre una tabla. Merece la pena
recordarlo: para muchos sistemas eso basta y no hace falta Kafka en absoluto.

Requiere consulta nativa: JPA no sabe expresar `SKIP LOCKED`, `LockModeType.PESSIMISTIC_WRITE`
genera `FOR UPDATE` y ahí acaba su vocabulario.

---

# 5 · El consumidor, y el módulo 4 volviendo a llamar

```java
@Incoming("events-in")
@Blocking          // ← sin esto, el bug número 1 del módulo 4
@Transactional
public void onEvent(String payload) { ... }
```

Los consumidores de Reactive Messaging se ejecutan **sobre un event loop de Vert.x**, y este método
hace JDBC, que bloquea. Sin `@Blocking` sería un consumidor de Kafka parando los event loops de
toda la aplicación, incluidas peticiones HTTP que no tienen nada que ver.

Es además de los sitios donde más se cuela, porque **un consumidor no «parece» un endpoint** y
nadie se pregunta en qué hilo corre.

## Consistencia eventual, dicha en voz alta

Entre que Inventario confirma y el catálogo se entera pasan unos segundos: el intervalo del relay
más la latencia de Kafka. Durante esa ventana el escaparate enseña un número antiguo.

**Es aceptable y es el diseño**, por lo que ya se dijo en el módulo 6: un número mostrado siempre
está atrasado, y nadie decide nada con él. Reservar va contra Inventario, que sí es la verdad.

## El consumer group decide si escalas o multiplicas trabajo

```properties
mp.messaging.incoming.events-in.group.id=marketplace-catalog
```

Todas las instancias con el **mismo** `group.id` se reparten las particiones: cada mensaje lo
procesa una sola. Con `group.id` distintos, **todas reciben todos los mensajes**.

Es la diferencia entre escalar horizontalmente y multiplicar el trabajo por el número de réplicas,
y es un error de configuración que no se nota hasta que hay más de una instancia.

---

# 6 · La saga de compra

```
1. reservar stock  ──→  2. cobrar  ──→  3. confirmar reserva
       │                    │
       │               ✗ rechazado
       └──────── liberar reserva  ← compensación
```

El paso 2 habla con un sistema externo que no participa en la transacción. **No hay ningún
`@Transactional` capaz de abarcar los tres pasos**, y tampoco lo habría si Inventario fuese un
servicio propio.

Una saga sustituye la atomicidad por **compensaciones**. Y no son simétricas: no se puede
«des-cobrar» una tarjeta, se emite un reembolso, que es **un hecho nuevo**. Por eso una saga es
*eventualmente* coherente y no atómica: hay instantes en los que el sistema está a medias, y el
diseño tiene que soportarlo.

## Orquestación, y su precio

El flujo se lee de arriba abajo en un método. Con coreografía —cada contexto reaccionando a los
eventos de los demás— el flujo **no está escrito en ningún sitio**: para saberlo hay que
reconstruirlo leyendo a qué evento responde cada consumidor, y los ciclos son fáciles de crear sin
querer.

El precio es que `PurchaseSaga` conoce Inventario y Pagos. Es acoplamiento consciente y localizado
en un punto, que suele ser mejor negocio que un flujo repartido por seis clases que nadie sigue.

## La clase NO lleva `@Transactional`, y es deliberado

Una transacción abierta durante la llamada al cobro retendría una conexión del pool durante
segundos — **el bug número 8 del módulo 4**. Con 20 conexiones, bastarían 20 compras simultáneas
para tumbar la aplicación entera. Cada paso abre y cierra la suya.

Y por eso `reserve` es un método aparte: los interceptores de CDI **solo actúan en llamadas que
entran desde fuera del bean**, así que un `@Transactional` invocado desde otro método de la misma
clase se ejecutaría sin transacción ninguna. La misma regla que obligó a crear
`TransactionalRunner` en el módulo 6.

## La clave de idempotencia del cobro

```java
chargeId = payments.charge(reserva.id().toString(), total);
```

La clave es **el id de la reserva**, no un UUID nuevo. Si la red se cae después de que el cargo se
procese pero antes de recibir la respuesta, el reintento con la misma clave devuelve el cargo
original en lugar de cobrar dos veces. Un UUID por intento daría cobros duplicados.

Todas las pasarelas reales lo soportan, y usarlo no es opcional.

## El orden de los pasos importa

Reservar primero y cobrar después evita cobrar por algo que no se puede servir. Al revés habría
que reembolsar, y **un reembolso siempre es peor experiencia que un «no hay stock»**.

## Cuando la compensación también falla

```java
catch (RuntimeException fallaLaCompensacion) {
    LOG.errorf(..., "it will be released when it expires");
    causaOriginal.addSuppressed(fallaLaCompensacion);
}
```

No es catastrófico porque **la reserva caduca sola** y el barrido del módulo 6 la devolverá: esa es
la red de seguridad que hace tolerable este caso. Y el fallo de la compensación no puede tapar el
motivo original — el comprador tiene derecho a saber que le rechazaron la tarjeta, no a recibir un
error de inventario.

---

# 7 · Lo que enseñaron los fallos

| Fallo | Lección |
|---|---|
| `SRMSG00073: cannot be used for both incoming and outgoing` | **El nombre del canal es interno de la aplicación; el topic es de Kafka.** Son cosas distintas: dos canales (`events-out`, `events-in`) sobre el mismo topic |
| Aserción sobre `payload.contains("\"available\":10")` | Atar un test al formato exacto que produzca Jackson lo rompe por motivos ajenos a lo que se prueba. Deserializar y comprobar el objeto |
| El `@Scheduled` publicando durante los tests | Un relay disparándose cada segundo convierte cualquier aserción sobre lo pendiente en una carrera. `%test.quarkus.scheduler.enabled=false` y llamada explícita |
| `PanacheListingRepositoryTest` fallando por 1 fila | Los tests nuevos usan transacciones reales, así que **lo que escriben, queda**. Los antiguos, con `@TestTransaction`, asumen base vacía: **quien no puede deshacer lo que escribe, recoge al salir** |

El tercero y el cuarto comparten moraleja: **al introducir asincronía, todo lo que antes era
determinista deja de serlo**. El planificador apagado en test y el `@AfterEach` son las dos
medidas que devuelven el control.

---

# 8 · Cómo probarlo

```bash
sdk env && ./gradlew quarkusDev    # levanta PostgreSQL, Keycloak y Kafka (Redpanda) solos

./gradlew test --tests "*OutboxTest*"           # la garantía transaccional
./gradlew test --tests "*StockProjectionTest*"  # el flujo completo hasta el catálogo
./gradlew test --tests "*PurchaseSagaTest*"     # la saga y sus compensaciones
```

Para mirar el outbox por dentro mientras corre en dev:

```sql
-- lo que está pendiente de publicar
select event_type, aggregate_id, occurred_at from outbox_event where published_at is null;

-- el retardo real entre que algo ocurre y que se publica
select event_type, published_at - occurred_at as latencia from outbox_event
 where published_at is not null order by occurred_at desc limit 10;
```

## Cosas para probar por tu cuenta

| Prueba | Qué esperar |
|---|---|
| Subir `marketplace.outbox.poll-interval` a `30s` | La consistencia eventual se vuelve visible: el escaparate tarda medio minuto |
| Quitar `@Blocking` del consumidor | El aviso `Thread has been blocked` del módulo 4, ahora desde Kafka |
| Cambiar `StockChanged` para que lleve un delta | El test de reprocesado se pone rojo: los duplicados dejan de ser inofensivos |
| Poner un `group.id` distinto por instancia | Todas procesan todos los mensajes: multiplicas trabajo en vez de repartirlo |
| Lanzar `republishAll()` a mano | Reproduce el duplicado que en producción llega solo |
| Quitar la clave de `Record.of(...)` | Los eventos se reparten por particiones y se pierde el orden por agregado |

## Los mandamientos

1. **Nunca escribas a la base de datos y a un broker en la misma operación.** Usa el outbox.
2. **Quien llama a `publish` no publica: promete publicar.**
3. **at-least-once es lo mejor que hay.** «Exactly-once» de extremo a extremo no existe.
4. **Haz idempotentes a los consumidores.** El duplicado no es una hipótesis, es una certeza.
5. **Manda estado, no incrementos**, y la idempotencia sale gratis.
6. **La clave de partición decide el orden.** Sin ella, tus eventos llegan barajados.
7. **`SKIP LOCKED` reparte trabajo entre réplicas** sin coordinador ni duplicados.
8. **`@Blocking` en los consumidores** que toquen la base de datos.
9. **Ninguna transacción abierta mientras hablas con un sistema externo.**
10. **Una saga compensa, no revierte.** Y la compensación también puede fallar: ten una red debajo.
