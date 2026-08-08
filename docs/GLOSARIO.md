# Glosario

Siglas y términos que van apareciendo, explicados sin dar nada por sabido.
**Este archivo crece con cada módulo** — si algo no está aquí, pídelo y lo añado.

## Ecosistema Java / Jakarta

| Sigla | Nombre completo | Qué es, en cristiano |
|---|---|---|
| **JVM** | Java Virtual Machine | La máquina virtual que ejecuta el bytecode. Compila a código máquina *en caliente* (JIT) mientras el programa corre. |
| **JIT** | Just In Time | Compilación durante la ejecución. La JVM observa qué código se usa mucho y lo optimiza al vuelo. Por eso una app Java "calienta". |
| **AOT** | Ahead Of Time | Compilación *antes* de ejecutar. Lo contrario de JIT. Es lo que hace GraalVM Native Image. |
| **Jakarta EE** | Jakarta Enterprise Edition | El antiguo Java EE, donado a la Eclipse Foundation. Por eso los paquetes son `jakarta.*` y ya no `javax.*`. Es un conjunto de **especificaciones**, no una implementación. |
| **CDI** | Contexts and Dependency Injection | La especificación estándar de inyección de dependencias en Jakarta. El equivalente estándar a lo que en Spring hace `@Component` / `@Autowired`. |
| **JAX-RS** | Java API for RESTful Web Services | La especificación de endpoints REST con anotaciones (`@Path`, `@GET`, `@Produces`). Hoy se llama **Jakarta REST**. |
| **JPA** | Jakarta Persistence API | La especificación de mapeo objeto-relacional (`@Entity`, `@Id`, `@Column`). |
| **ORM** | Object-Relational Mapping | Traducir entre objetos Java y filas de una base de datos relacional. Hibernate es *la* implementación de referencia. |
| **JDBC** | Java Database Connectivity | La API de bajo nivel para hablar con bases de datos SQL. Es **bloqueante** por diseño — dato importante para el módulo reactivo. |
| **MicroProfile** | — | Conjunto de especificaciones para microservicios sobre Jakarta: Config, Health, Metrics, Fault Tolerance, OpenAPI, JWT. Quarkus las implementa todas. |

## Específico de Quarkus

| Término | Qué significa |
|---|---|
| **Augmentation** | La fase de build donde Quarkus hace todo el trabajo que otros frameworks hacen al arrancar: escaneo, resolución de inyección, generación de bytecode. Es *la* idea central de Quarkus. |
| **Extensión** | El equivalente a un "starter" de Spring Boot, pero con dos módulos: `deployment` (solo build time) y `runtime` (lo que se empaqueta). |
| **`@BuildStep`** | Método que se ejecuta durante la augmentation. Consume y produce `BuildItem`s, formando un grafo de pasos de build. |
| **`BuildItem`** | Unidad de información que viaja entre `@BuildStep`s. Es cómo se comunican las extensiones entre sí en build time. |
| **`@Recorder`** | Clase cuyos métodos, al ser llamados desde un `@BuildStep`, **no se ejecutan**: se *graban* como bytecode que correrá al arrancar la app. El truco que traslada configuración a startup pregenerado. |
| **ARC** | La implementación de CDI de Quarkus. Resuelve la inyección en build time y genera clases reales (`Foo_Bean`, `Foo_ClientProxy`) en vez de usar reflection. |
| **Jandex** | Índice de anotaciones offline (`META-INF/jandex.idx`). Quarkus lo lee en vez de escanear el classpath con reflection. |
| **Dev Services** | Si declaras una extensión que necesita infraestructura (Postgres, Kafka, Redis) y no le das URL, Quarkus levanta un contenedor Docker automáticamente en dev y test. Cero configuración. |
| **Live reload** | En `quarkusDev`, al llegar una petición Quarkus comprueba si cambió el código fuente, y si sí, recompila y recarga en caliente. |
| **Dev UI** | Consola web en `/q/dev-ui` durante desarrollo: ver beans, config, endpoints, ejecutar tests, inspeccionar caché. |
| **`/q/`** | Prefijo reservado para los endpoints de gestión: `/q/health`, `/q/metrics`, `/q/openapi`, `/q/dev-ui`. |
| **Build-time config** | Propiedad que queda *congelada* en el artefacto y **no** se puede cambiar con una variable de entorno en producción. Distinguirla de la runtime config evita sorpresas al desplegar. |
| **`@Unremovable`** | ARC borra del binario los beans que nadie inyecta. Esta anotación lo impide, para beans que se resuelven de forma dinámica. |

## Rendimiento y runtime

| Término | Qué significa |
|---|---|
| **GraalVM** | JVM alternativa de Oracle que, entre otras cosas, sabe compilar Java a un ejecutable nativo. |
| **Mandrel** | Distribución de GraalVM mantenida por Red Hat, recortada a solo lo necesario para Native Image. Es la que se usa con Quarkus. |
| **Native Image** | Ejecutable nativo compilado AOT. Arranca en ~15 ms y consume ~30 MB de RAM, a cambio de perder dinamismo. |
| **Closed world** | La hipótesis de Native Image: todo el código alcanzable se conoce en compilación. Por eso rompen la reflection no declarada y los proxies dinámicos. |
| **RSS** | Resident Set Size. La memoria RAM física que ocupa un proceso. La métrica que de verdad importa para calcular cuántos pods caben en un nodo. |
| **Netty** | Librería de red asíncrona de alto rendimiento. La base sobre la que se apoya todo el I/O de Quarkus. |
| **Vert.x** | Toolkit reactivo sobre Netty. Es el motor de I/O interno de Quarkus, aunque casi nunca lo tocas directamente. |
| **Event loop** | Hilo que atiende muchas conexiones en un bucle sin bloquearse nunca. Quarkus arranca 2 por núcleo. **Bloquear un event loop es el pecado capital.** |
| **Worker thread** | Hilo de un pool tradicional donde se ejecuta el código bloqueante, para no bloquear los event loops. |
| **Virtual thread** | Hilo ligero gestionado por la JVM (Java 21+). Permite escribir código bloqueante y sencillo con un coste parecido al reactivo. |
| **Mutiny** | La librería reactiva de Quarkus. Sus dos tipos son `Uni<T>` (0 o 1 resultado) y `Multi<T>` (flujo de N). Conceptualmente muy cerca de `IO` de Cats Effect o `ZIO`. |
| **Backpressure** | Mecanismo por el que un consumidor lento le dice al productor que frene, en vez de reventar por memoria. |

## Modelado y arquitectura

| Término | Qué significa |
|---|---|
| **Value Object** | Objeto sin identidad propia, definido solo por sus valores e inmutable. `Money` es el ejemplo canónico: 10 € es 10 €, da igual qué instancia sea. |
| **ADT** | Algebraic Data Type. Un tipo definido como "una de estas N alternativas". En Java se expresa con `sealed interface` + `record`; en Scala con `sealed trait` + case classes. |
| **Record pattern** | Desestructurar un record dentro de un `switch` o `instanceof`, ligando sus componentes a variables: `case Fulfillable(var total) -> ...`. |
| **Exhaustividad** | Propiedad de un `switch` sobre un tipo `sealed`: el compilador verifica que cubres todos los casos y no necesitas rama `default`. |
| **Bounded context** | Frontera dentro de la que un término del negocio tiene un único significado. "Listing" significa una cosa en catálogo y otra en inventario; el bounded context lo hace explícito. |
| **Saga** | Transacción distribuida partida en pasos locales, cada uno con su acción compensatoria. Se usa cuando no hay una transacción ACID que abarque varios servicios. |
| **Outbox** | Patrón para publicar eventos de forma fiable: en vez de escribir en la BD *y* en Kafka (que no es atómico), escribes el evento en una tabla dentro de la misma transacción y un proceso aparte lo publica. |
| **Idempotencia** | Que repetir una operación dé el mismo resultado que hacerla una vez. Imprescindible cuando la red puede duplicar mensajes. |
| **Puerto / Adaptador** | El puerto es una interfaz que **el dominio** define para lo que necesita; el adaptador la implementa desde fuera. Invierte la dependencia: el dominio no depende de la base de datos, la base de datos se adapta al dominio. |

## API y HTTP

| Término | Qué significa |
|---|---|
| **DTO** | Data Transfer Object. Objeto cuya única función es viajar por la API, separado del modelo de dominio para que el JSON público no quede acoplado a la estructura interna. |
| **Bean Validation** | Especificación de Jakarta (implementada por Hibernate Validator) para validar de forma declarativa con anotaciones: `@NotBlank`, `@Positive`, `@Pattern`. Devuelve **todas** las infracciones a la vez. |
| **`@Valid`** | Lo que dispara la validación en cascada de un objeto. Sin él, las anotaciones del DTO se ignoran en silencio. |
| **`ExceptionMapper`** | Componente JAX-RS que traduce un tipo de excepción a una respuesta HTTP. Es lo que permite que el dominio lance excepciones sin saber nada de códigos de estado. |
| **RFC 7807** | *Problem Details for HTTP APIs*. Formato estándar de cuerpo de error, con `type`, `title`, `status`, `detail` e `instance`. Se sirve como `application/problem+json`. |
| **`application/problem+json`** | El media type del RFC 7807. Permite a un cliente distinguir por la cabecera si recibió el recurso o una explicación de por qué no. |
| **OpenAPI** | Especificación para describir APIs REST de forma legible por máquinas. Antes se llamaba Swagger. Quarkus lo genera en build time desde tus anotaciones. |
| **Swagger UI** | Interfaz web que renderiza un documento OpenAPI y permite probar los endpoints. En Quarkus vive en `/q/swagger-ui`. |
| **Discriminador** | Campo del JSON (`"type": "PRODUCT"`) que indica de qué variante concreta se trata, para que el cliente —y el deserializador— sepan qué esperar. |
| **201 + `Location`** | La respuesta correcta a una creación: el código dice que se creó, y la cabecera dice dónde vive, sin que el cliente tenga que componer la URL. |
| **409 Conflict** | "La petición está bien; es el estado del servidor el que no encaja". Distinto de 400, que dice "arregla la petición". |
| **ISO 4217** | El estándar de códigos de moneda de tres letras: EUR, USD, JPY. Define también cuántos decimales tiene cada una. |
| **IANA time zone** | Identificador de zona horaria como `Europe/Madrid`, frente a un desfase fijo como `+02:00`. Conserva la intención a lo largo de los cambios de horario de verano. |

## Persistencia

| Término | Qué significa |
|---|---|
| **Flyway** | Herramienta de migraciones de esquema versionadas. Ficheros `V<n>__descripcion.sql` que se aplican en orden y quedan registrados, de forma que cualquier entorno se reconstruye igual. |
| **Panache** | Capa de Quarkus sobre Hibernate ORM que elimina boilerplate. Sus métodos son `default` vacíos cuyo cuerpo Quarkus genera en build time dentro de tu clase. |
| **Dirty checking** | Hibernate guarda una instantánea de cada entidad al cargarla y, al cerrar la transacción, emite un UPDATE por cada diferencia. Modificar una entidad gestionada ya la persiste, sin llamar a nada. |
| **Caché de primer nivel** | La sesión de Hibernate recuerda las entidades que ya cargó y las sirve sin volver a la base de datos. Evita consultas repetidas, y en procesos por lotes largos se convierte en fuga de memoria. |
| **N+1** | Cargar N elementos y lanzar una consulta extra por cada uno. No falla ni aparece en los logs: solo hace la aplicación lenta cuando ya hay datos reales. |
| **Presupuesto de consultas** | Test que fija cuántas sentencias SQL puede emitir una operación. Convierte un N+1 —invisible para los tests funcionales— en un build rojo. |
| **Lost update** | Dos transacciones leen el mismo valor, ambas lo modifican y la segunda pisa a la primera. Nadie falla y el descuadre solo se descubre contando a mano. |
| **Bloqueo optimista** | `@Version`: un contador que va en el `WHERE` del UPDATE. Si otro escribió antes, el UPDATE afecta a cero filas y salta `OptimisticLockException`. No bloquea a nadie. |
| **Bloqueo pesimista** | `SELECT … FOR UPDATE`: toma un lock real sobre la fila y los demás esperan. Nadie repite trabajo, pero serializa el acceso y puede provocar deadlocks. |
| **MVCC** | *Multi-Version Concurrency Control*. Cómo PostgreSQL permite leer sin bloquear: cada transacción ve una versión coherente de los datos. |
| **Paginación por offset** | `LIMIT/OFFSET`. Simple y con salto a página arbitraria, pero degrada linealmente: la página 5.000 obliga a recorrer y descartar 100.000 filas. |
| **Paginación por keyset** | «Dame los N siguientes a este valor». Cuesta lo mismo a cualquier profundidad, a cambio de perder el salto a una página concreta. La de los feeds con scroll infinito. |
| **Orden total** | Criterio de ordenación que no deja empates, normalmente añadiendo la clave primaria. Sin él, la paginación duplica y pierde elementos. |
| **SINGLE_TABLE** | Estrategia JPA de herencia: toda la jerarquía en una tabla con columna discriminadora. Sin joins al leer, a costa de columnas anulables. |
| **`bpchar`** | El tipo real detrás de `CHAR(n)` en PostgreSQL: *blank-padded*, rellena con espacios hasta la longitud fija. Casi nunca es lo que quieres. |
| **`@TestTransaction`** | Anotación de Quarkus que envuelve un test en una transacción y hace rollback al terminar. Solo sirve si el test y el código bajo prueba comparten hilo. |
