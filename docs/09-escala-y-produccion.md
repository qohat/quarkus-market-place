# Módulo 9 — Escala y producción

El último módulo, y el que cobra la inversión del primero. En el módulo 0 dijimos:

> *Quarkus mueve a build time lo que otros frameworks hacen al arrancar: escanear anotaciones,
> resolver inyección, generar proxies.*

Aquí se ve para qué servía.

---

# 1 · Qué es realmente un binario nativo

Lo que **no** es: no es «traducir Java a C».

`native-image` hace **análisis estático de accesibilidad** (*points-to analysis*): parte de tu
`main()` y sigue cada llamada, cada campo y cada rama, construyendo el grafo de todo lo alcanzable.
Después **tira lo que no está en él** — de tu código, de las dependencias y del JDK.

```
JAR + dependencias + JDK entero
        ↓  análisis de accesibilidad
solo lo que tu programa puede llegar a ejecutar
        ↓  compilación AOT a código máquina
un ejecutable con SU PROPIA máquina virtual dentro
```

Ese último punto sorprende: el binario lleva dentro **SubstrateVM**, una JVM reducida con
recolector de basura y gestión de hilos, pero **sin cargador de clases, sin intérprete y sin JIT**.
No los necesita, porque ya no hay bytecode que cargar.

## La pieza que explica los 92 milisegundos

Un arranque normal de JVM hace:

```
1. arrancar la JVM
2. leer del disco miles de ficheros .class
3. VERIFICAR el bytecode de cada uno
4. ejecutar los inicializadores estáticos
5. interpretar mientras el JIT observa
6. ...y entonces empezar a servir
```

El binario nativo **no hace nada de eso**, y no porque sea más rápido haciéndolo: porque **ya está
hecho**.

> Durante la compilación, GraalVM ejecuta los inicializadores estáticos y guarda el estado de
> memoria resultante —los objetos ya construidos, con sus campos rellenos— dentro del ejecutable.
> Se llama **image heap**, y al arrancar simplemente se **mapea en memoria**.

Tus objetos de configuración y las estructuras internas del framework **ya existen** cuando el
proceso arranca. No se construyen: se leen del fichero.

Es exactamente la idea de la augmentation del módulo 0, llevada un paso más allá.

---

# 2 · El mundo cerrado, y qué rompe

El precio es que **todo debe ser predecible al compilar**. Y hay cosas en Java que son, por diseño,
impredecibles:

```java
Class.forName("com.marketplace." + nombreQueVieneDeUnFichero)
```

Esa clase no aparece en el grafo, así que se elimina, y en runtime obtienes un
`ClassNotFoundException` sobre una clase que existe en tu código fuente.

| | Por qué se rompe |
|---|---|
| **Reflection** | `getMethod("nombre")` no es rastreable |
| **Proxies dinámicos** | Se generan en runtime; ya no hay generador |
| **Serialización** | Suele ir por reflection |
| **Recursos** | No hay classpath del que leerlos |
| **JNI** | Código nativo que llama a Java por nombre |

Y lo peligroso: **estos fallos no aparecen al compilar**. El binario se genera tan feliz y revienta
en runtime, solo en la rama que usa esa clase.

Se arreglan contándoselo —`@RegisterForReflection`, ficheros `reflect-config.json`— que en un
proyecto tradicional es una lista enorme y frágil.

## Por qué Quarkus encaja sin que tú escribas nada

**Quarkus casi no usa nada de eso en runtime**, y no por contenerse: ya lo había resuelto para
arrancar rápido en JVM. Lo tienes comprobado desde el módulo 0, en tu propio artefacto:

```
ListingCatalog_Bean.class          ← ARC resolvió la inyección al compilar
ListingCatalog_ClientProxy.class   ← el proxy es una clase REAL, no dinámica
ListingResource$quarkusrestinvoker$publish_....class   ← invokevirtual directo
MoneyView$quarkusjacksonserializer.class               ← hasta el serializador
```

Cada una existe **como fichero**. El análisis de accesibilidad las encuentra sin esfuerzo porque
alguien las llama explícitamente.

> Un framework tradicional descubre su configuración leyendo anotaciones con reflection al
> arrancar, que es justo lo que el mundo cerrado prohíbe. **Quarkus no compila bien a nativo por
> casualidad: nativo es la razón por la que se diseñó así.**

Y cuando una extensión necesita reflection, **registra ella misma** los metadatos durante la
augmentation. Por eso esta aplicación —con Hibernate, Kafka, OIDC, OpenTelemetry y Jackson—
compiló sin una sola línea de configuración de GraalVM.

---

# 3 · La medición

Mismo código, mismas dependencias, ambos en contenedor, contra el mismo PostgreSQL y el mismo
Kafka.

| | JVM | Nativo | |
|---|---:|---:|---|
| **Arranque** | 1,933 s | **0,092 s** | **21× más rápido** |
| **Memoria residente** | 406 MiB | **36 MiB** | **11× menos** |
| **Throughput** (c=200) | 1 864 req/s | 1 844 req/s | **empate** |
| **p99** | 119 ms | 132 ms | JVM algo mejor |
| Tiempo de compilación | segundos | **1m 23s** | |
| Tamaño de imagen | 803 MB | 364 MB | |

## El empate del throughput es el resultado interesante

La teoría dice que el nativo rinde peor en régimen permanente, porque el JIT optimiza con el
perfil real de ejecución y puede recompilar, mientras que un binario nativo se optimizó una vez y
a ciegas.

**Aquí no se ve, y el motivo es el módulo 4.** El endpoint espera 100 ms de I/O simulada, así que
el cuello de botella son los 200 hilos del worker pool, no la ejecución de código:

```
200 workers ÷ 0,1 s = 2 000 req/s de techo
medido: 1 844 (nativo) y 1 864 (JVM)
```

Los dos están contra el mismo techo, y ese techo no lo pone el compilador.

> **La desventaja de rendimiento del nativo es real, pero solo se manifiesta cuando el cuello de
> botella es la CPU.** En un servicio de negocio típico —que se pasa la vida esperando a la base de
> datos o a otro servicio— no aparece. Es la misma lección del módulo 4 con otro disfraz: el
> recurso escaso manda, y casi nunca es el que uno tiene en la cabeza.

## Cuándo elegir cada uno

```
nativo →  el arranque y la memoria son tu cuello de botella
JVM    →  el throughput sostenido con CPU saturada es tu cuello de botella
```

Serverless, escalado a cero, cientos de réplicas pequeñas, CLIs → nativo.
Un servicio que arranca una vez y sirve tráfico durante semanas → JVM, casi siempre.

Y una consecuencia económica que se olvida: **11× menos memoria significa 11× más réplicas por el
mismo dinero**. En Kubernetes eso puede pesar más que cualquier diferencia de throughput.

---

# 4 · Caché con invalidación dirigida

El catálogo se lee muchísimo más de lo que se escribe, así que `browse()` se cachea. Pero lo
interesante es la invalidación.

## Qué se cachea y qué no

```java
@CacheResult(cacheName = "catalog-browse")
public Page<Listing> browse(PageRequest pageRequest)     // ✓ resultado compartido

public Page<Listing> ownedBy(SellerId seller, ...)       // ✗ una entrada por usuario
```

`ownedBy` se le parece pero **su resultado depende del vendedor**: habría una entrada por persona,
con una tasa de acierto pésima y memoria gastada para servir a uno solo. **Cachear lo que no se
comparte es pagar memoria por nada.**

## Invalidación por evento, no por tiempo

Lo habitual es poner un tiempo de expiración y aceptar servir datos viejos hasta que venza. Aquí no
hace falta: el módulo 7 dejó un evento que dice **exactamente** cuándo cambió el stock.

```java
// StockProjectionUpdater, al procesar StockChanged
if (filas > 0) {
    catalog.invalidateBrowseCache();
}
```

El orden importa: **primero escribir, después invalidar**. Al revés habría una ventana en la que
otra petición repuebla la caché con el valor *antiguo* y lo deja fijado hasta la siguiente
invalidación.

El `expire-after-write=5M` se queda solo como red de seguridad, por si alguna vía de cambio no
generase evento.

## El bug que la caché introdujo, y que un test cazó

```
Expected: a collection with size <0>
Actual:   [{availableUnits=10, id=a6f00844-...}]
```

Un test archivaba una publicación y esperaba que desapareciera del escaparate. **Seguía ahí.**

La invalidación por evento cubría el stock, pero **archivar cambia la visibilidad** y no genera
ningún evento de inventario. Hubo que invalidar también en las transiciones de estado.

> Es la lección incómoda de cachear: **hay que enumerar todo lo que puede cambiar el resultado**, y
> basta olvidar una vía para servir datos viejos indefinidamente. La caché no introdujo un fallo
> evidente: introdujo uno que solo aparece si alguien mira.

Y un detalle de CDI que aparece por **tercera vez** en el curso: `@CacheInvalidateAll` va en los
métodos públicos, no en el `transitionTo` privado, porque **los interceptores solo actúan en
llamadas que entran desde fuera del bean**. Igual que `@Transactional` en el módulo 6 y que
`reserve` en la saga del 7.

---

# 5 · Control de admisión

Cierra el círculo del módulo 4, donde medimos que un servicio saturado **no daba ni un error**:
aceptaba todo y respondía en un segundo lo que costaba cien milisegundos. La conclusión era que
*saturar sin errores es peor que fallar*. Esto es fallar, a propósito y pronto.

## Cubo de fichas

Cada cliente tiene un cubo de `capacity` fichas que se repone a `refillPerSecond`. Cada petición
gasta una; sin fichas, **429 con `Retry-After`**.

Frente a un contador por ventana fija, **tolera ráfagas**: un cliente que ha estado callado acumula
fichas y las gasta de golpe, que es como se comporta una pantalla al abrirse. Un contador por
minuto rechazaría esa ráfaga legítima y, a la vez, dejaría pasar el doble de tráfico justo en el
cambio de ventana.

## Tres decisiones que importan

**El límite es por cliente**, y se prefiere el usuario autenticado a la IP: detrás de una IP puede
haber una empresa entera saliendo por el mismo NAT. Un límite global convertiría a un solo bot en
una denegación de servicio para todos.

**Los health checks van exentos.** Si el rate limiting alcanzara a `/q/health`, Kubernetes recibiría
429 al sondear un servicio saturado, lo sacaría del balanceador y —con liveness— lo reiniciaría. El
control de admisión habría convertido una sobrecarga pasajera en una caída.

**`Retry-After` no es decoración.** Sin esa cabecera, un cliente bien programado reintenta a ciegas
y uno mal programado reintenta en bucle: justo lo que no quieres de quien ya pedía demasiado.

## Sus dos límites, dichos claramente

**Es por instancia.** Con tres réplicas el límite real es el triple. Para un límite global hace
falta estado compartido —Redis— y entonces cada petición cuesta una llamada de red. En la práctica
esto suele vivir en el balanceador o la pasarela de API, donde el límite sí es global y no consume
recursos de la aplicación.

**El mapa crece.** Una entrada por cliente sin caducidad es una fuga lenta y un vector de ataque:
basta rotar identificadores. En producción sería una caché con expiración.

---

# 6 · Lo que enseñaron los fallos

| Fallo | Lección |
|---|---|
| `Outputting both native and JAR packages is not currently supported` | Hay que desactivar el JAR explícitamente |
| `UnsatisfiedResolutionException` para `JsonWebToken` | **`quarkus.oidc.enabled` es BUILD TIME**: apagarlo elimina el bean y rompe la inyección al compilar. La de runtime es `tenant-enabled` |
| `exec format error` | `container-build` produce un binario **de Linux**. Un ejecutable nativo es específico de SO y arquitectura |
| `UnsupportedClassVersionError` en la imagen JVM | **Tercera vez** con las versiones de Java: el daemon de Gradle, el toolchain y ahora la imagen base. «Uso Java 25» es una propiedad de *cada proceso*, no del proyecto |
| Las variables de entorno no sobrescribían la config | El **perfil de build queda grabado en el artefacto**, y sus propiedades `%bench.` ganan. Las de sistema (`-D`) sí mandan |
| **53 220 req/s en JVM contra 1 861 en nativo** | **Comparaba código distinto**: el binario nativo se compiló antes de escribir el rate limiter, y la JVM devolvía 429 a espuertas. **El mismo error del módulo 4, cometido otra vez** |
| La caché ocultaba una publicación archivada | Enumerar *todas* las vías de cambio, no solo la que tienes en mente |

El sexto es el que más duele y el que más enseña. En el módulo 4 escribimos que el error de bulto
es **cambiar dos variables a la vez y atribuir la diferencia a la que uno tenía en mente** — y aquí
volvió a pasar, con un número tan absurdo (53 000 req/s sobre un endpoint que duerme 100 ms) que
por suerte era imposible de creer.

> La defensa no fue la experiencia, fue **mirar los códigos de estado**. Un número que no se puede
> explicar con la aritmética que ya conoces es un número equivocado.

---

# 7 · Cómo reproducirlo

```bash
# 1. Compilar el binario nativo (contenedor con Mandrel, sin instalar GraalVM)
sdk env
./gradlew build -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -Dquarkus.native.container-build=true \
  -Dquarkus.native.native-image-xmx=5g \
  -Dquarkus.profile=bench -x test

# 2. Y la variante JVM del MISMO código
./gradlew quarkusBuild -Dquarkus.profile=bench

# 3. Las dos imágenes
docker build -f src/main/docker/Dockerfile.native  -t marketplace:native .
docker build -f src/main/docker/Dockerfile.jvm25   -t marketplace:jvm .

# 4. Red y dependencias
docker network create mp-net
docker run -d --name mp-bench-db --network mp-net -e POSTGRES_USER=marketplace \
  -e POSTGRES_PASSWORD=marketplace -e POSTGRES_DB=marketplace postgres:18
docker run -d --name mp-bench-kafka --network mp-net \
  docker.redpanda.com/redpandadata/redpanda:latest redpanda start \
  --overprovisioned --smp 1 --memory 512M --node-id 0 --check=false \
  --kafka-addr PLAINTEXT://0.0.0.0:9092 \
  --advertise-kafka-addr PLAINTEXT://mp-bench-kafka:9092

# 5. Arrancar (OJO: propiedades -D, no variables de entorno — ver los fallos)
docker run -d --name mp-native --network mp-net -p 8091:8080 marketplace:native \
  -Dquarkus.datasource.jdbc.url=jdbc:postgresql://mp-bench-db:5432/marketplace \
  -Dquarkus.datasource.username=marketplace -Dquarkus.datasource.password=marketplace \
  -Dquarkus.datasource.reactive.url=postgresql://mp-bench-db:5432/marketplace \
  -Dkafka.bootstrap.servers=mp-bench-kafka:9092 -Dquarkus.scheduler.enabled=false

# 6. Medir
docker logs mp-native | grep "started in"
docker stats --no-stream mp-native mp-jvm
hey -z 20s -c 200 http://localhost:8091/bench/blocking
```

**Y antes de creerte cualquier número, mira los códigos de estado.**

## Cosas para probar por tu cuenta

| Prueba | Qué esperar |
|---|---|
| Medir `/bench/db/blocking` en vez del sintético | El pool de conexiones iguala aún más a los dos |
| `docker run --memory=64m` la variante JVM | No arranca; el nativo sí |
| Añadir un `Class.forName` dinámico y recompilar | El binario compila y falla en runtime |
| Quitar `@CacheInvalidateAll` de `archive` | El test del ciclo de vida se pone rojo otra vez |
| Subir la carga del rate limiter en el perfil bench | Medir cuántos 429/s emite, que no es rendimiento |

## Los mandamientos

1. **Nativo no es «más rápido»**: arranca antes y ocupa menos. El JIT gana con CPU saturada.
2. **La ventaja de throughput del JIT no aparece** si tu cuello de botella es I/O.
3. **11× menos memoria son 11× más réplicas** por el mismo dinero.
4. **Un binario nativo es de un SO y una arquitectura.** No hay «compila una vez».
5. **El perfil de build queda grabado en el artefacto.**
6. **Cachea lo compartido, nunca lo que depende del usuario.**
7. **Invalida por evento cuando puedas**, y enumera *todas* las vías de cambio.
8. **Rechaza pronto**: un 429 con `Retry-After` es mejor servicio que un 200 a los cinco segundos.
9. **Los health checks nunca se limitan.**
10. **Un número que no puedes explicar con aritmética es un número equivocado.**
