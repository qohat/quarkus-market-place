# Módulo 4 — Concurrencia: bloqueante, virtual threads y reactivo

El módulo que decide si una aplicación escala. Todo lo anterior era estructura; esto es capacidad.

Nada de lo que sigue es teoría: cada número de este documento salió de una medición reproducible
en esta máquina, y todos los experimentos se pueden repetir con los scripts del repositorio.

---

# 1 · La ley de Little

```
peticiones_en_vuelo  =  throughput  ×  latencia
         L           =      λ       ×     W
```

No es una heurística: es aritmética, y tu servidor la obedece con menos del 1 % de error (lo
comprobamos en el experimento 2). Para servir 10.000 req/s con 100 ms de latencia hacen falta
1.000 peticiones vivas a la vez. No hay forma de esquivarlo.

La pregunta de todo el módulo es **qué representa físicamente cada una de esas peticiones en
vuelo**:

| Modelo | Cada petición en vuelo es… | Coste |
|---|---|---|
| Hilo por petición | un hilo del sistema operativo | ~1 MB de stack + cambio de contexto en el kernel |
| Virtual thread | un objeto en el heap | ~1 KB, crece por segmentos |
| Reactivo | una continuación | cientos de bytes |

Los tres modelos resuelven el mismo problema pagando en monedas distintas.

---

# 2 · Cómo decide Quarkus, y la única regla que hay que recordar

Quarkus se apoya en **Vert.x**. Hay `2 × núcleos` **event loops** (24 en la máquina donde se
midió esto) y por ellos pasa todo el I/O de red. Un event loop parado no deja de atender a una
petición: deja de atender a todas las que tenía asignadas.

La clasificación de cada endpoint se hace **en build time**, a partir del tipo de retorno:

```
¿devuelve Uni / Multi / CompletionStage?
   sí  →  NO BLOQUEANTE  →  event loop
   no  →  BLOQUEANTE     →  worker pool
```

Y las anotaciones **no añaden comportamiento: corrigen esa clasificación cuando el tipo de
retorno miente**.

| Anotación | Qué corrige | Cuándo hace falta de verdad |
|---|---|---|
| `@Blocking` | «devuelvo `Uni`, pero por dentro bloqueo» | Un `Uni` que envuelve una llamada JDBC |
| `@NonBlocking` | «devuelvo `String`, pero no bloqueo nada» | Respuesta trivial; ahorra el salto de hilo |
| `@RunOnVirtualThread` | «soy bloqueante, ejecútame en un hilo virtual» | El cuerpo bloquea de verdad |

Si el tipo de retorno ya dice la verdad, la anotación sobra. Y a veces es peligrosa
(ver bug nº 13).

Que la decisión se tome al compilar se puede comprobar en el artefacto: por cada método aparece
un invoker generado.

```
SyntheticBenchmarkResource$quarkusrestinvoker$blocking_d9c59af1....class
SyntheticBenchmarkResource$quarkusrestinvoker$virtual_669b5049....class
SyntheticBenchmarkResource$quarkusrestinvoker$reactive_a8994d0c....class
```

---

# 3 · Los bugs más comunes

Agrupados por causa raíz, porque casi todos son la misma equivocación vista desde otro ángulo.

## Familia 1 · «Creo que no bloqueo, pero bloqueo»

| # | Bug | Síntoma | Por qué pasa |
|---|---|---|---|
| 1 | **JDBC dentro de un método que devuelve `Uni`** | `Thread has been blocked for 2013 ms`, y cae el servidor entero | Devolver `Uni` es una promesa tuya. Nadie la verifica |
| 2 | **`@Blocking` olvidado** en un endpoint que llama a una API lenta | Igual, pero intermitente y solo bajo carga | Con poco tráfico, 100 ms de bloqueo no se notan |
| 3 | **Bloquear en `@PostConstruct`** | Arranque lento o timeouts en la primera petición | La inicialización perezosa la paga la primera petición, a veces sobre un event loop |

## Familia 2 · «El contexto no viaja con el trabajo»

| # | Bug | Síntoma | Por qué pasa |
|---|---|---|---|
| 4 | **`ThreadLocal` cruzando un salto de hilo** | El `userId` del log es de **otra petición** | Reactivo cambia de hilo entre pasos; el `ThreadLocal` se queda atrás |
| 5 | **`@Transactional` sobre código reactivo** | La transacción no cubre lo que crees | JTA ata la transacción al hilo con un `ThreadLocal` |
| 6 | **Estado mutable en un recurso REST** | Datos de un usuario en la respuesta de otro | Quarkus REST hace los recursos **singleton** |

El nº 4 no es un bug de rendimiento sino de **auditoría y seguridad**: tus logs mienten sobre
quién hizo qué, y no aparece jamás en desarrollo porque hace falta concurrencia real.

## Familia 3 · «El recurso escaso no era el que yo creía»

| # | Bug | Síntoma | Por qué pasa |
|---|---|---|---|
| 7 | **Pool de conexiones < concurrencia real** | p99 por las nubes con la CPU al 10 % | La cola ante el pool no sale en las métricas de la aplicación |
| 8 | **Llamada HTTP dentro de una transacción** | Pool agotado con poquísimo tráfico | La conexión queda retenida toda la llamada remota: 20 conexiones × 2 s = 10 req/s de techo |
| 9 | **`ThreadLocal` pesado × miles de virtual threads** | `OutOfMemoryError` con poco tráfico | 1 MB por hilo era razonable con 200 hilos, no con 200.000. La respuesta moderna es `ScopedValue` |
| 10 | **`synchronized` alrededor de I/O** (*pinning*) | Los virtual threads no rinden más | Hasta Java 23 fijaba el hilo a su carrier. **JEP 491 lo arregló en Java 24**; JNI y `Object.wait()` siguen fijando |

## Y tres que no son de código

| # | Bug | Por qué duele |
|---|---|---|
| 11 | **`Uni` que nadie suscribe** | Sin suscripción **no pasa nada**. Sin excepción, sin log. Tu código no se ejecutó |
| 12 | **Coordinated omission al medir** | El benchmark dice p99 = 5 ms y producción dice 2 s: la herramienta dejó de enviar mientras el servidor sufría |
| 13 | **Anotación de concurrencia contradictoria** | Quarkus la acepta sin quejarse. No cuesta rendimiento, pero documenta una garantía que no existe |
| 14 | **Comparar dos cosas cambiando dos variables** | Nos pasó en el experimento 4: dos pools de tamaño distinto disfrazados de dos modelos de concurrencia |

---

# 4 · El laboratorio

`SyntheticBenchmarkResource` y `DatabaseBenchmarkResource`, en `com.marketplace.benchmark`.

Los tres modelos resolviendo **exactamente el mismo trabajo**:

```java
@GET @Path("/blocking")
public String blocking() throws InterruptedException {
    Thread.sleep(IO_LATENCY);
    return thread();
}

@GET @Path("/virtual")
@RunOnVirtualThread
public String virtual() throws InterruptedException {
    Thread.sleep(IO_LATENCY);           // cuerpo IDÉNTICO al anterior
    return thread();
}

@GET @Path("/reactive")
public Uni<String> reactive() {
    return Uni.createFrom().item(SyntheticBenchmarkResource::thread)
            .onItem().delayIt().by(IO_LATENCY);
}
```

Que los dos primeros sean idénticos carácter por carácter **es** la promesa de Loom: código
bloqueante normal, depurable, con stack traces legibles, y comportamiento de escalado de un
sistema asíncrono.

Comprobación de que cada uno corre donde debe:

| Endpoint | Hilo que atiende |
|---|---|
| `/blocking` | `executor-thread-1` |
| `/virtual` | `VirtualThread[#61]/runnable@ForkJoinPool-1-worker-1` |
| `/reactive` | `vert.x-eventloop-thread-9` |

En el del medio, ese `@` es el **carrier thread**: el hilo de plataforma que lo ejecuta ahora
mismo. Al llegar el `sleep`, el virtual thread se desmonta y el carrier queda libre.

## El laboratorio solo existe en el perfil `bench`

`@IfBuildProfile("bench")` hace que ARC lo elimine del artefacto normal. No queda protegido:
queda **inexistente**. Comprobado con `unzip -l generated-bytecode.jar`: en un build normal no
aparece ninguna clase de `com/marketplace/benchmark`.

Dos razones: un endpoint que duerme a petición del cliente es un DoS regalado, y un benchmark
tiene que correr sobre un artefacto de producción — medir en dev mode no significa nada.

---

# 5 · Los experimentos, uno a uno

Todos reproducibles. Ver la sección 7 para el arranque.

## Experimento 1 · Las anotaciones que sobran

**Pregunta:** ¿qué pasa si pongo `@Blocking` en el método que devuelve `String`?

**Respuesta:** nada. Ya estaba clasificado como bloqueante por su tipo de retorno. `@Blocking`
es un no-op. Enseña que la anotación **no es lo que hace que algo se ejecute en un worker**.

**Pregunta:** ¿y `@RunOnVirtualThread` sobre el método que devuelve `Uni`?

**Predicción:** que Quarkus lo rechace al compilar, porque es una contradicción.
**Resultado real: compila, arranca y funciona.** El método pasa a ejecutarse en un hilo virtual:

```
reactive -> VirtualThread[#64,quarkus-virtual-thread-1]/runnable@ForkJoinPool-1-worker-1
```

Y el rendimiento no cambia: **19 357 req/s con la anotación, 19 287 sin ella**. Dentro del ruido.

**Por qué no cambia, y por qué eso es peor:**

```
1. Quarkus crea un hilo virtual                       ~1 µs
2. Ejecuta el cuerpo: construye el Uni y se suscribe  ~1 µs
3. El método retorna. El hilo virtual MUERE
4. ...100 ms después el temporizador dispara en el event loop
```

El hilo virtual vive dos microsegundos y muere justo antes del trabajo que creías cubrir. El
peligro no es el coste, es la **falsa seguridad**:

```java
@RunOnVirtualThread                          // «tranquilo, esto está cubierto»
public Uni<Listing> buscar(String id) {
    return Uni.createFrom().item(() -> repo.findById(id));   // ...pues no
}
```

Ese `findById` se ejecuta cuando alguien se suscribe, y quien se suscribe es el event loop. Es
el bug nº 1 con una anotación encima que dice lo contrario.

## Experimento 2 · El escenario sintético y la ley de Little

`./scripts/bench.sh` — 100 ms de I/O simulada, sin base de datos.

| Concurrencia | Modelo | req/s | p50 | p99 |
|---:|---|---:|---:|---:|
| **50** | blocking | 467 | 107 ms | 112 ms |
| | virtual | 454 | 111 ms | 115 ms |
| | reactive | 486 | 103 ms | 106 ms |
| **500** | blocking | **1 887** | **263 ms** | 311 ms |
| | virtual | 4 456 | 112 ms | 127 ms |
| | reactive | 4 823 | 103 ms | 111 ms |
| **2000** | blocking | **1 896** | **1 048 ms** | 1 068 ms |
| | virtual | 18 331 | 108 ms | 130 ms |
| | reactive | 19 287 | 102 ms | 112 ms |

**Con 50 en vuelo los tres son indistinguibles.** Por debajo del punto de saturación, elegir
reactivo no aporta nada y solo cuesta complejidad.

**El bloqueante se congela en ~1 890 req/s**: `200 workers ÷ 0,1 s = 2 000`. Cuadruplicas la
carga de 500 a 2000 y el throughput no se mueve.

Si el throughput está clavado, la carga extra va a la latencia. Comprobando `W = L / λ`:

```
c=500    →  500 / 1887 = 0,265 s     predicho 265 ms   ·   medido 263 ms
c=2000   →  2000 / 1896 = 1,055 s    predicho 1055 ms  ·   medido 1048 ms
```

**Menos del 1 % de error.**

**Y cero errores en las tres filas.** El servidor saturado no falló: solo tardó 1 segundo en
hacer algo de 100 ms. Un panel que mida errores y CPU dirá que está *perfecto* — 0 % de errores
y la CPU ociosa, porque todos los hilos duermen. Por eso la métrica que importa es la p99.

**Virtual vs reactivo: 18 331 contra 19 287.** Un 5 % de diferencia, pagado con un modelo de
programación distinto, stack traces inservibles y `@Transactional` que deja de funcionar. Para
la mayoría de servicios ese 5 % no justifica el precio.

## Experimento 3 · Bloquear el event loop (bug nº 1)

Un endpoint que **miente**: firma no bloqueante, cuerpo que bloquea.

```java
@GET @Path("/lie")
public Uni<String> lie(@QueryParam("ms") @DefaultValue("100") long ms)
        throws InterruptedException {
    Thread.sleep(ms);                       // sobre un event loop
    return Uni.createFrom().item(thread());
}
```

**El detector de bloqueos de Vert.x** salta a partir de 2 s, con el culpable y su línea:

```
WARN [io.vertx.core.impl.BlockedThreadChecker] Thread Thread[vert.x-eventloop-thread-0]
     has been blocked for 2897 ms, time limit is 2000 ms
	at java.base/java.lang.Thread.sleep(Thread.java:540)
	at com.marketplace.benchmark.SyntheticBenchmarkResource.lie(SyntheticBenchmarkResource.java:105)
```

Ojo a lo que **no** avisa: un bloqueo de 100 ms nunca dispara el aviso, y aun así es devastador.

**El daño colateral es lo importante.** Midiendo `/bench/reactive` —que no ha cambiado ni una
línea— mientras `/bench/lie` recibe carga:

| `/bench/reactive` | En calma | Con el bug activo en OTRO endpoint | |
|---|---:|---:|---|
| req/s | 968 | **19,7** | **49× peor** |
| p50 | 103 ms | **4 844 ms** | 47× peor |
| p99 | 109 ms | **5 287 ms** | 48× peor |

Un endpoint impecable pasó de 0,1 s a casi 5 s. Está caído a efectos prácticos, y **la CPU está
ociosa**: 24 hilos durmiendo y un servidor que no responde.

En un equipo real, la monitorización dirá que *`/reactive` tiene una p99 de 5 segundos*. Irás a
mirar su código y es perfecto. El culpable está en otro módulo, escrito por otra persona.

> La diferencia esencial: en el modelo bloqueante, un endpoint lento se come su parte del pool
> y perjudica a los demás **proporcionalmente**. En el reactivo, un endpoint que bloquea los
> tumba a **todos, catastróficamente**. El reactivo da más rendimiento a cambio de una
> disciplina que deja de ser opcional.

**La cura** es una anotación, y el resultado es contraintuitivo:

| Endpoint | req/s | p50 | p99 |
|---|---:|---:|---:|
| `/lie` — sin `@Blocking` | 115 | 1 653 ms | 3 318 ms |
| `/fixed` — con `@Blocking` | **1 919** | **104 ms** | **110 ms** |

**16,7× más throughput por marcar el endpoint como bloqueante.** No porque bloquear sea bueno,
sino porque el recurso escaso deja de ser 24 event loops y pasa a ser 200 workers. La anotación
no cambia el trabajo: cambia **dónde se hace la cola**.

## Experimento 4 · El error de medición (bug nº 14)

`PREFIJO=/bench/db ./scripts/bench.sh`, primera pasada, con la configuración por defecto:

| Conc. | blocking | virtual | reactive |
|---:|---:|---:|---:|
| 50 | 3 658 | 3 670 | **1 428** |
| 500 | 3 759 | 3 770 | **1 422** |
| 2000 | 3 789 | 3 725 | **1 433** |

La conclusión fácil habría sido «el reactivo rinde 2,6 veces peor». **Es falsa.** Contando
conexiones reales en PostgreSQL durante la carga:

```sql
select count(*) from pg_stat_activity
 where datname='marketplace' and state='active' and query like '%pg_sleep%';
```

```
blocking / virtual  →  ~50 conexiones ejecutando la consulta
reactive            →  ~21 conexiones
```

Los valores por defecto de los dos pools **no coinciden**. Y la ley de Little lo confirma:

```
blocking:  50 conexiones ÷ 3759 req/s  =  13,3 ms por conexión
reactive:  20 conexiones ÷ 1433 req/s  =  14,0 ms por conexión
```

**El mismo tiempo por conexión en ambas pilas.** No se estaban comparando dos modelos de
concurrencia, sino dos tamaños de pool.

**Lección:** al comparar tecnologías, cambia **una** variable. Y verifica el recurso escaso
midiéndolo en su origen —aquí, `pg_stat_activity`— en vez de deducirlo de la configuración.

## Experimento 5 · La hipótesis del módulo, con los pools igualados

Fijando `jdbc.max-size=20` y `reactive.max-size=20`:

| Conc. | blocking | virtual | reactive |
|---:|---:|---:|---:|
| 50 | 1 391 | 1 398 | 1 399 |
| 500 | 1 385 | 1 379 | 1 366 |
| 2000 | 1 381 | 1 443 | 1 366 |

**Idénticos, a cualquier carga.** `20 conexiones ÷ 14,4 ms = 1 389 req/s`.

Las dos tablas juntas son el módulo entero:

```
SIN base de datos (c=2000)    blocking  1 896   virtual 18 331   reactive 19 287   →  10×
CON base de datos (c=2000)    blocking  1 381   virtual  1 443   reactive  1 366   →   1×
```

La ventaja de 10× era real y se evapora en cuanto hay una base de datos detrás.
**Los virtual threads no regalan capacidad de base de datos.** Nadie lo hace.

---

# 6 · Por qué Hibernate es bloqueante — la cadena exacta

El bloqueo no está en Hibernate: está en la **firma de JDBC**, y es irreparable desde dentro.

## La API no puede decir «todavía no»

```java
// java.sql.Statement
public abstract boolean execute(String) throws SQLException;
public abstract ResultSet executeQuery(String) throws SQLException;
```

`boolean`, `ResultSet`. Cuando llamas, **tiene que devolverte el resultado**: no existe un valor
que signifique «te aviso cuando llegue». Con esa firma, la única implementación posible es
esperar.

```java
// io.vertx.sqlclient.PreparedQuery
public abstract io.vertx.core.Future<T> execute(io.vertx.sqlclient.Tuple);
```

`Future<T>`. Ahí sí cabe. **Toda la diferencia está en esa línea**; el resto es consecuencia.

## Dónde duerme físicamente el hilo (pgjdbc 42.7.13)

```java
// org/postgresql/core/PGStream.java:479
public int receiveChar() throws IOException {
    int c = pgInput.read();          // ← aquí se para todo
    ...
}

// org/postgresql/core/PGStream.java:304
pgInput = new VisibleBufferedInputStream(connection.getInputStream(), 8192);
//                                       └─ java.net.Socket, modo bloqueante

// org/postgresql/core/VisibleBufferedInputStream.java:192
read = wrapped.read(buffer, endIndex, canFit);   // ← syscall recv()
```

Esa última línea es el fondo del pozo: el kernel deja el hilo *waiting* hasta que lleguen bytes.

## Qué hace distinto el cliente reactivo

```java
public class io.vertx.pgclient.impl.PgSocketConnection
        extends io.vertx.sqlclient.impl.SocketConnectionBase
// ...que hereda de io.vertx.core.net.impl.ConnectionBase  ← un handler de Netty
```

No hay ningún `read()`: hay un `Selector` (`epoll` / `kqueue`) con miles de sockets registrados.
El kernel avisa cuando hay datos y Netty invoca `channelRead(...)`.

| | pgjdbc | Vert.x PgClient |
|---|---|---|
| Quién pregunta | el hilo: «¿hay bytes?» y espera | el kernel: «hay bytes» y llama |
| Sockets por hilo | 1 | miles |
| Reimplementa | nada, usa JDBC | **el protocolo wire de PostgreSQL entero** |

Esa última fila es el precio real: no envuelve JDBC, lo tira y reimplementa el protocolo binario
desde cero. Por eso no existe «JDBC reactivo»: habría que reescribir todos los drivers.

## Y por qué Hibernate ORM además no se puede salvar

```java
var listing = repo.findById(id);      // 1 consulta
listing.getSeller().getName();        // ← ¿otra consulta? depende del estado de la sesión
```

**El lazy loading dispara I/O desde un getter.** Para hacerlo reactivo, cada acceso a un campo
tendría que devolver `Uni<String>`, lo que destruye el modelo de objetos. Añade que la `Session`
es *stateful* y no thread-safe, y que `@Transactional` vive en un `ThreadLocal`.

Por eso **Hibernate Reactive es un proyecto aparte**, no un modo de Hibernate: exige `Uni` en
todas las firmas y prohíbe el lazy loading implícito.

> Y el detalle que cierra el círculo con Loom: los virtual threads **sí** funcionan con JDBC,
> porque desde Java 21 los sockets del JDK (`sun.nio.ch.NioSocketImpl`) están adaptados: cuando
> el driver bloquea, la JVM aparca el hilo virtual en lugar de dormir el del SO. Puedes usar
> Hibernate tal cual sobre virtual threads. Es el argumento más fuerte a favor de Loom: **te da
> el escalado sin tirar tu capa de persistencia a la basura.**

---

# 7 · Cómo reproducirlo todo

```bash
# 1. Base de datos (Dev Services solo actúa en dev y test, no en un artefacto de producción)
docker run -d --name mp-bench-db \
  -e POSTGRES_USER=marketplace -e POSTGRES_PASSWORD=marketplace \
  -e POSTGRES_DB=marketplace -p 55432:5432 postgres:18

# 2. Generador de carga
brew install hey

# 3. Construir y arrancar en perfil bench
#    OJO: el daemon de Gradle tiene que correr sobre Java 25, no solo el toolchain.
#    Basta con `sdk env` en la shell antes de invocar a Gradle.
sdk env
./gradlew quarkusBuild -Dquarkus.profile=bench
java -Dquarkus.profile=bench -jar build/quarkus-app/quarkus-run.jar

# 4. Los dos escenarios
./scripts/bench.sh                        # sintético, sin base de datos
PREFIJO=/bench/db ./scripts/bench.sh      # con PostgreSQL real
```

Variables del script: `BASE_URL`, `PREFIJO`, `DURACION`, `CONCURRENCIAS`, `MODELOS`.

## Cosas para probar por tu cuenta

| Prueba | Qué esperar |
|---|---|
| `quarkus.thread-pool.max-threads=50` y repetir el sintético | El techo del bloqueante baja de ~1 890 a ~500 req/s |
| `quarkus.datasource.jdbc.max-size=50` y repetir el de BD | El bloqueante sube a ~3 700 y el reactivo se queda en 1 400 |
| `CONCURRENCIAS="5000 10000" ./scripts/bench.sh` | Dónde empieza a fallar `hey` antes que el servidor |
| Cargar `/bench/lie?ms=500` mientras mides `/bench/blocking` | El bloqueante aguanta: no comparte los event loops del mismo modo |
| Contar conexiones con `pg_stat_activity` durante cada prueba | Ver el recurso escaso en su origen |

## Los mandamientos, en corto

1. **Nunca bloquees un event loop.** Si dudas, `@Blocking`.
2. **El tipo de retorno es una promesa.** Nadie la verifica; incumplirla tumba el servidor.
3. **Mide el recurso escaso en su origen**, no en la configuración.
4. **Cambia una variable cada vez.**
5. **Por debajo de la saturación, los tres modelos son iguales.** Elige por mantenibilidad.
6. **Virtual threads son la opción por defecto razonable** en Java 21+: el escalado del
   reactivo con el modelo de programación del bloqueante.
7. **Reactivo solo cuando el cuello de botella sean de verdad los hilos** y no haya una base de
   datos con pool acotado detrás.
8. **Saturar sin errores es peor que fallar.** Una cola infinita convierte un fallo rápido y
   visible en uno lento e invisible: por eso existe el control de admisión.
