# Banco de preguntas y respuestas

Todas las preguntas de repaso del curso, con su respuesta razonada. Están planteadas como en
una entrevista técnica: lo que se valora no es el dato, sino el porqué y los trade-offs.

Crece con cada módulo.

---

# Módulo 0 — Fundamentos de Quarkus

### 0.1 · ¿Por qué ARC necesita `@Unremovable`? ¿Qué patrón de Spring deja de ser posible?

El patrón que muere es **`applicationContext.getBean(MiServicio.class)`**: pedirle beans al
contenedor en runtime, por tipo o por nombre.

En Spring el contenedor es un mapa vivo, consultable en cualquier momento. En Quarkus el grafo de
inyección se resuelve y se **cierra** en build time. ARC ve que nadie inyecta `MiServicio`,
concluye que es código muerto y **lo borra del artefacto**. Tu `getBean()` falla en runtime sobre
una clase que existe en el código fuente pero no en el binario.

`@Unremovable` es la forma de decirle a ARC "no lo ves usado, pero consérvalo".

El caso realista son las implementaciones elegidas por configuración:

```java
@ApplicationScoped class StripeGateway implements PaymentGateway { }
@ApplicationScoped class PaypalGateway implements PaymentGateway { }
```

Aquí pasan dos cosas distintas, que conviene no confundir:

- **Si inyectas `PaymentGateway` a secas**, el build falla con `Ambiguous dependencies`. Dos
  candidatos, ningún desempate. Es un error de compilación — Spring, en cambio, arrancaría y
  fallaría al primer uso.
- **Si resuelves dinámicamente** (`CDI.current().select(...)` con un `@Named` construido desde
  un string de config), ARC no puede seguir el rastro, borra ambas implementaciones y obtienes
  `UnsatisfiedResolutionException`. Ese es el caso de `@Unremovable`.

Pero la lección de fondo es que **en Quarkus ese patrón es un olor**. Lo idiomático es decidir en
build time, y así el binario solo contiene la implementación que se va a usar:

```java
@ApplicationScoped
@IfBuildProperty(name = "payments.gateway", stringValue = "stripe")
class StripeGateway implements PaymentGateway { }
```

Familia de herramientas: `@IfBuildProperty`, `@LookupIfProperty`, `@DefaultBean`, `@Alternative`
+ `@Priority`, `@IfBuildProfile`.

### 0.2 · Si una propiedad es *build-time config*, ¿qué implica para tu pipeline de despliegue?

Que **queda congelada en el artefacto** durante la augmentation. No la cambias con una variable
de entorno, ni con un ConfigMap de Kubernetes, ni con un argumento de arranque: hay que
reconstruir la imagen.

Las tres consecuencias prácticas:

1. **Rompe "build once, deploy anywhere"** si dos entornos difieren en build-time config. Ya no
   despliegas el mismo artefacto en staging y en producción, sino dos artefactos distintos — con
   todo lo que eso implica para la trazabilidad de lo que has probado.
2. **Un cambio de esa propiedad es un cambio de código**, con su build, sus tests y su
   despliegue. No es un ajuste operativo.
3. **Quarkus lo detecta y avisa** al arrancar si ve que has intentado sobrescribir una propiedad
   fijada en build time. No falla en silencio, pero tampoco aplica el valor.

Ejemplos típicos: `quarkus.datasource.db-kind` (determina qué driver se empaqueta),
`quarkus.http.root-path`, los registros de reflection para native.

**Regla práctica:** mantén idéntica la build-time config entre entornos, y deja en runtime config
todo lo que varía — URLs, credenciales, tamaños de pool, timeouts. La documentación de Quarkus
marca cada propiedad build-time con un candado 🔒.

### 0.3 · Un endpoint hace una consulta JDBC. ¿En qué hilo debe correr y por qué?

**En un worker thread, nunca en un event loop.** JDBC es bloqueante por especificación:
`executeQuery()` detiene el hilo hasta que la base de datos responde. Un event loop atiende
miles de conexiones simultáneas; bloquearlo durante 50 ms las congela todas. Quarkus incluso lo
detecta y lo denuncia en el log.

Tres opciones reales, de menor a mayor sofisticación:

| Opción | Cómo | Coste |
|---|---|---|
| Worker thread | Es el **default** para métodos síncronos. También `@Blocking`. | Un hilo del sistema por petición en vuelo. Techo en unos pocos miles. |
| `@RunOnVirtualThread` | Java 21+. El bloqueo desmonta el virtual thread sin bloquear el hilo portador. | Mucha más densidad con el mismo código imperativo. |
| Cliente reactivo | Cambiar JDBC por el cliente Postgres de Vert.x y devolver `Uni`. Entonces sí, event loop. | Código reactivo, más difícil de leer y depurar. |

**El matiz que separa una buena respuesta de una excelente:** el cuello de botella real casi nunca
son los hilos, sino **el pool de conexiones**. Si el pool tiene 20 conexiones, da igual que tengas
un millón de virtual threads — 20 consultas concurrentes es tu techo. Los virtual threads mejoran
la densidad de peticiones *en espera*, no la capacidad de la base de datos. Lo mediremos en el
módulo 4.

### 0.4 · ¿Por qué la compilación nativa rompe con reflection no declarada?

Native Image hace un **análisis estático de alcanzabilidad** bajo hipótesis de *closed world*:
parte de los puntos de entrada, sigue todas las llamadas, y **elimina del binario todo lo que no
demuestre ser alcanzable**. Es lo que reduce el ejecutable a decenas de megas en vez de arrastrar
la JVM entera.

La reflection es invisible a ese análisis:

```java
Class.forName(propiedades.get("handler.class"));   // el string se conoce en runtime
```

El compilador no puede saber qué clase es. La marca como no alcanzable, la elimina, y en runtime
obtienes `ClassNotFoundException`. Lo mismo con `getDeclaredMethod`, los proxies dinámicos, los
recursos del classpath y la serialización.

La solución es **declararlo** (`reflect-config.json`, `@RegisterForReflection`) para que el
compilador lo trate como alcanzable.

Y aquí está la conexión con el módulo entero: **las extensiones de Quarkus ya lo hacen por ti
durante la augmentation**. Cuando `quarkus-hibernate-orm` procesa tus `@Entity`, además de generar
el metamodelo registra la reflection que Hibernate va a necesitar. Por eso una aplicación Quarkus
compila a nativo sin que tú toques un fichero de configuración, mientras que hacerlo a mano en
otro framework es un trabajo considerable.

---

# Módulo 1 — Dominio y Java moderno

### 1.1 · ¿Por qué normalizar la escala en el compact constructor y no en un método `normalize()`?

Porque el compact constructor es **el único punto por el que pasan todas las construcciones** del
record: las tuyas, las de Jackson al deserializar, las de `Money.of()`, las de cada operación que
devuelve un `Money` nuevo. Un invariante forzado ahí es un invariante de verdad.

Un `normalize()` externo depende de que alguien se acuerde de llamarlo. En el momento en que un
solo camino lo olvide, tienes un `Money` con escala sin normalizar circulando por el sistema, y
entonces `equals` empieza a mentir en sitios impredecibles. Un invariante que depende de la
disciplina del programador no es un invariante: es una convención.

**Regla general:** los invariantes se fuerzan en el constructor. Si un objeto puede existir en
estado inválido aunque sea un instante, alguien acabará observándolo en ese estado.

### 1.2 · Un `switch` exhaustivo sobre un tipo `sealed` no necesita `default`. ¿Qué pasa si lo añades igual?

**Compila sin problema** — no es un error, ni siquiera un warning en la mayoría de configuraciones.

Lo que pierdes es toda la garantía. Con `default`, al añadir un tercer tipo de `Listing` el switch
**sigue compilando**, y el caso nuevo cae silenciosamente en la rama por defecto. Acabas de
convertir un error de compilación (que te habría llevado de la mano por cada punto a revisar) en
un bug de runtime que descubrirás cuando un cliente se queje.

**Regla:** nunca pongas `default` en un `switch` sobre un tipo `sealed`. Si varios casos comparten
comportamiento, enuméralos explícitamente:

```java
case ProductListing p, ServiceListing s -> ...;   // explícito, sigue siendo exhaustivo
```

### 1.3 · `FulfillmentCheck` es un ADT. ¿Cuándo **no** merece la pena y basta un `boolean`?

Un ADT se justifica cuando se cumplen las tres:

1. Hay **más de un motivo** de fallo.
2. Los motivos llevan **datos distintos** (`available` solo tiene sentido en un caso).
3. El llamante va a **actuar distinto** según el motivo.

Si falta alguna, es ceremonia. `isEmpty()`, `isVisible()`, `hasExpired()` son genuinamente
binarias: no hay submotivos que distinguir, y envolverlas en un ADT solo añade tipos.

Tampoco vale la pena si el consumidor no ramifica —si lo único que hace con el resultado es
registrarlo en un log, un mensaje basta— ni cuando hay un único modo de fallo, donde un
`Optional` es más simple y más idiomático.

### 1.4 · ¿Por qué `Money` permite importes negativos si un precio nunca lo es?

Porque **`Money` no es "precio"**: es un value object que modela una cantidad de dinero, y las
cantidades negativas son perfectamente reales — reembolsos, ajustes contables, saldos deudores,
el resultado intermedio de un cálculo.

Si `Money` prohibiera negativos, `a.minus(b)` podría lanzar en mitad de un cálculo válido, y
tendrías que ir comprobando el orden de las restas por todas partes.

La restricción "un precio debe ser positivo" es una regla de **`ProductListing`**, y ahí es donde
está — su compact constructor valida `price.isPositive()`.

**Regla general:** el value object modela el concepto en toda su amplitud; las restricciones de
negocio viven en la entidad que las impone. Meterlas en el value object lo vuelve inservible para
el resto del sistema.

### 1.5 · Si `Listing` fuera un único tipo con campos opcionales, ¿dónde estaría el bug al reservar stock?

En que **el compilador nunca te obligaría a distinguir los dos casos**. Con este modelo:

```java
class Listing { String type; Integer stock; Duration slot; }
```

la reserva se escribe así, y el bug puede tomar dos formas:

```java
listing.stock - cantidad          // NullPointerException con un servicio
```

o, peor, la variante que **no** falla: alguien inicializa `stock = 0` para los servicios "porque
un `Integer` nulo da problemas", y entonces reservar una clase de guitarra devuelve
educadamente "sin stock" para siempre. Un bug silencioso, en producción, que nadie asocia con el
modelo de datos.

Con `sealed`, el `switch` te obliga a escribir la rama del servicio antes de compilar. El error
se descubre al escribirlo, no al desplegarlo.

### 1.6 · ¿Por qué Java exige que los tipos de `permits` estén en el mismo módulo o paquete?

Porque la exhaustividad solo es demostrable si el compilador puede **ver todas las
implementaciones** al compilar cualquier `switch` sobre el tipo sellado.

Si `permits` pudiera apuntar a un tipo en otro módulo que quizá ni esté en el classpath en tiempo
de compilación, el compilador no podría verificar que un `switch` cubre todos los casos, ni la JVM
hacer cumplir el sellado al cargar clases. La restricción es lo que convierte `sealed` en una
garantía verificable en lugar de una declaración de intenciones.

En concreto: dentro de un módulo con nombre, los subtipos deben estar **en el mismo módulo**; en
el módulo sin nombre (el classpath de toda la vida), **en el mismo paquete**.

---

# Módulo 2 — REST y ARC

### 2.1 · ¿Qué le impide a Spring generar un invoker con llamada directa, como hace Quarkus?

**El momento en que sabe qué existe.**

Spring descubre los endpoints *al arrancar*, escaneando anotaciones por reflection. Para entonces
las clases ya están compiladas y cargadas, así que generar un invoker exigiría emitir bytecode en
runtime y cargarlo con un classloader propio. Spring sabe hacerlo —es exactamente lo que hace con
CGLIB para los proxies AOP— pero tiene dos costes prohibitivos:

1. Se paga **en cada arranque**, justo lo que se quiere reducir.
2. Sigue siendo generación dinámica de clases, **incompatible con el closed world** de la
   compilación nativa.

Quarkus lo sabe **antes de compilar**, así que emite la clase como parte del artefacto: coste de
arranque cero y perfectamente nativo. Puedes verlo con `./scripts/inspeccionar-arc.sh`.

> Para ser justos: Spring 6 / Boot 3 introdujeron **Spring AOT**, que sí genera código en build
> time para GraalVM. Es convergencia real hacia este modelo. La diferencia es que en Spring es un
> modo opcional añadido, mientras que en Quarkus es *cómo funciona el framework*, siempre.

### 2.2 · `ListingCatalog` es `@ApplicationScoped` y no tiene estado mutable. ¿Por qué importa?

Porque hay **una sola instancia para toda la aplicación**, y todos los hilos de petición la usan a
la vez. Cualquier campo mutable sería estado compartido sin sincronizar: condiciones de carrera,
lecturas rancias por el modelo de memoria de Java, corrupción bajo carga.

Con un único campo `final` que referencia otro bean, la clase es **intrínsecamente thread-safe** y
atiende miles de peticiones concurrentes sin un solo lock.

Es la regla general de los beans de scope largo: **o no tienen estado mutable, o su estado es
explícitamente thread-safe** (como el `ConcurrentHashMap` de `InMemoryListingRepository`). Si
necesitas estado por petición, el mecanismo correcto es `@RequestScoped`, no un campo.

### 2.3 · `browse()` devuelve `List<Listing>`, el tipo sellado. ¿Qué pasa al serializarlo a JSON?

**Al serializar** (salida) funciona: Jackson mira el tipo en runtime de cada elemento y vuelca sus
campos.

**Al deserializar** se rompe. Dado un JSON, Jackson necesita instanciar algo, y `Listing` es una
interfaz sin constructor. No tiene forma de saber si debe construir un `ProductListing` o un
`ServiceListing` salvo que el JSON lleve un discriminador explícito y la jerarquía esté anotada
con `@JsonTypeInfo`.

Hay un problema más sutil aunque la salida "funcione": los campos emitidos dependerían del tipo
concreto, así que tu contrato de API quedaría definido por un detalle interno del dominio.
Renombrar un campo en `ProductListing` rompería a tus clientes sin que nada te avisara.

Por eso introdujimos DTOs: `ListingResponse` es un tipo concreto, el problema desaparece, y el
contrato queda fijado por tests.

### 2.4 · `MoneyView.amount` es un `String`. Un compañero propone `number`. ¿Qué le respondes?

Que **JSON no tiene un tipo decimal**. El número JSON es un literal cuya interpretación depende
enteramente del parser, y el parser más extendido del planeta —`JSON.parse` en el navegador— lo
convierte a `double` IEEE-754. Consecuencias:

```
25.00   ->  25          (se pierde la escala, y con ella el formato del precio)
0.1+0.2 ->  0.30000000000000004
```

Para importes grandes o con muchos decimales, el error deja de ser cosmético. Es el bug clásico de
las APIs de pago consumidas desde el navegador. Como string, el valor llega intacto y el cliente
decide si lo pasa por una librería de decimales.

**Cuándo tendría razón:** si controlas todos los consumidores y ninguno es JavaScript. Java con
`USE_BIG_DECIMAL_FOR_FLOATS`, C# con `decimal` o Python con `parse_float=Decimal` preservan el
valor sin problema. Además, un campo numérico es más natural para herramientas de generación de
clientes, validadores de esquema y consultas analíticas.

La pregunta que resuelve la discusión es: **¿hay un navegador en algún punto de la cadena?** Si lo
hay, o si la API es pública y no sabes quién la consumirá, string. Si es interna y tipada de punta
a punta, número es defendible.

### 2.5 · `ListingResponse.from(listing)` es estático en el DTO. ¿Y si fuera `listing.toResponse()`?

**Invertiría la dirección de la dependencia.** `Listing` tendría que importar `ListingResponse`,
así que el **dominio pasaría a depender de la capa REST** — exactamente lo contrario de lo que
persigue puertos y adaptadores, donde las dependencias apuntan hacia dentro.

Los daños concretos:

1. El dominio deja de compilar sin la infraestructura, y sus tests dejan de ser puros.
2. El dominio adquiere conocimiento de un protocolo específico, cuando debería ser agnóstico.
3. No escala: cuando aparezcan gRPC y los eventos de Kafka (módulo 7), `Listing` acumularía
   `toProto()`, `toEvent()`, `toCsvRow()`… convirtiéndose en el punto donde convergen todas las
   capas externas.

**Regla:** el mapeo pertenece siempre al adaptador que lo necesita. Cada representación externa
sabe cómo leer el dominio; el dominio no sabe de ninguna.

### 2.6 · `ListingResource` es singleton. ¿Qué pasa si le añades un `private int contador` y lo incrementas en cada método?

Falla, y conviene saber *exactamente* por qué, porque "no es thread-safe" se queda corto. Hay
**tres** fallos superpuestos:

1. **Pérdida de incrementos.** `contador++` no es una operación atómica: son tres pasos (leer,
   sumar, escribir). Dos hilos pueden leer el mismo valor, sumar uno cada uno y escribir el mismo
   resultado. Dos peticiones, un solo incremento.
2. **Visibilidad entre hilos.** Sin `volatile` ni sincronización, el Modelo de Memoria de Java no
   garantiza que un hilo llegue a ver nunca lo que escribió otro. El valor puede quedarse en la
   caché de un núcleo indefinidamente. Esto no es teórico: es la razón de que estos bugs
   desaparezcan al añadir un `println`, que introduce una barrera de memoria.
3. **Contención.** Aunque lo arreglaras con `AtomicInteger`, todas las peticiones estarían
   compitiendo por escribir en la misma línea de caché. Bajo carga real, ese *false sharing*
   degrada el rendimiento de forma medible.

Y hay un cuarto problema, más de fondo: **el contador desaparece al reiniciar el pod y cada
réplica lleva el suyo**. Un contador en memoria en un sistema que escala horizontalmente no mide
nada útil. Lo correcto es un contador de Micrometer (módulo 8), que además se agrega entre
instancias.

### 2.7 · Si ya devolvemos el objeto completo en el cuerpo, ¿qué aporta la cabecera `Location`?

Cuatro cosas que el cuerpo no da:

1. **Es la respuesta estándar a "¿dónde vive esto ahora?"**. Un cliente genérico —una librería
   HTTP, un test runner, una herramienta de API— sabe seguir un `Location` sin conocer tu esquema
   JSON. Si el id va solo en el cuerpo, hay que documentar en qué campo está y cómo se compone la
   URL.
2. **Desacopla la construcción de URLs.** El cliente no tiene que saber que el recurso vive en
   `/listings/{id}`. Si mañana mueves el recurso o añades un prefijo de versión, los clientes que
   siguen el `Location` no se enteran.
3. **Sobrevive a respuestas sin cuerpo.** Es habitual que un `POST` de creación devuelva 201
   vacío por eficiencia. Con `Location`, ese caso sigue siendo utilizable.
4. **Habilita caché y redirecciones intermedias.** Proxies y CDNs entienden `Location` sin
   parsear el payload.

El contrarrestante honesto: **devolver también el cuerpo ahorra un round-trip**. Por eso hacemos
las dos cosas. Devolver solo `Location` obligaría a cada cliente a un `GET` inmediato.

### 2.8 · Los mappers capturan `IllegalArgumentException`. ¿Qué riesgo tiene, y cómo se arregla?

**El riesgo:** son excepciones del JDK, no tuyas. Las lanza `Integer.parseInt`, las lanza media
biblioteca estándar, y las lanza cualquier dependencia de terceros ante un bug **tuyo**. Al
mapearlas a 400 se producen tres daños:

1. Un fallo interno se presenta como culpa del cliente.
2. Desaparece de las métricas de error 5xx, así que **ninguna alerta se dispara**.
3. El cliente reintenta una petición que nunca va a funcionar, porque el 400 le dice que el
   problema es suyo.

Es un fallo especialmente traicionero porque **degrada la observabilidad justo en los casos que
más te importa observar**.

**Mitigación parcial** (lo que hace este proyecto): registrar la excepción completa en WARN, para
que quede rastro aunque la respuesta diga 4xx.

**La solución correcta:** que el dominio lance excepciones propias —una jerarquía tipo
`DomainValidationException`— y que los mappers cubran únicamente esas. Todo lo demás se convierte
en un 500 honesto, que es lo que un fallo interno debe ser. Se paga con más tipos, y se cobra en
que un 4xx significa de verdad "el cliente se equivocó".

### 2.9 · El live reload dijo *Restarting* y los datos en memoria desaparecieron. ¿Por qué?

Porque Quarkus **reinicia la aplicación**; no hace hot-swap de bytecode sobre un proceso vivo.

Lo que hace es más listo que un reinicio normal: mantiene la JVM arrancada, conserva el
classloader de aumentación y solo vuelve a ejecutar la augmentation y a reconstruir el contexto
de la aplicación con las clases recompiladas. Por eso tarda ~0,4 s en vez de los segundos de un
arranque en frío.

Pero reconstruir el contexto de la aplicación significa **descartar todos los beans y crearlos de
nuevo**. Nuestro `@ApplicationScoped` con el `ConcurrentHashMap` se recreó vacío, y con él se
fueron los listings.

Es la limitación fundamental del estado en memoria, y no solo en desarrollo: en producción, cada
despliegue, cada reinicio de pod y cada escalado a cero se lleva ese estado por delante. Es
exactamente la motivación del módulo 3.

*Nota complementaria:* Quarkus distingue entre cambios que puede aplicar así y cambios que exigen
un reinicio completo. Tocar `build.gradle` o una propiedad de **build time** obliga a rearrancar
de verdad — otra consecuencia práctica de la pregunta 0.2.


---

# Módulo 3 — Persistencia

### 3.1 · Tu suite crece a 400 tests de integración y tarda 15 minutos. ¿Qué haces?

Lo primero es entender de dónde sale el tiempo, porque hay dos costes distintos y se atacan de
formas opuestas.

**El arranque de la aplicación.** Quarkus **reutiliza la misma instancia** entre clases
`@QuarkusTest` mientras la configuración no cambie. El asesino silencioso es todo lo que fuerza un
reinicio: `@TestProfile` distinto, `@QuarkusTestResource`, `@InjectMock` sobre beans distintos.
Cada variación crea otra instancia. Ordenar los tests para **agrupar los que comparten
configuración** puede reducir el tiempo a la mitad sin tocar ni un test.

**El contenedor.** Testcontainers levanta uno por ejecución. Con `testcontainers.reuse.enable=true`
en `~/.testcontainers.properties`, el contenedor sobrevive entre ejecuciones locales — enorme
ganancia en el bucle de desarrollo, aunque en CI no aplique.

Después, en orden de rentabilidad:

1. **Mover tests hacia abajo en la pirámide.** La mayoría de los "tests de integración" no prueban
   integración: prueban lógica que podría vivir en un test de dominio. Nuestros 106 tests corren en
   diez segundos porque solo los que de verdad necesitan base de datos la usan.
2. **Paralelizar**, pero con cuidado: exige aislamiento real entre tests. Los que usan
   `@TestTransaction` lo toleran; los que limpian una tabla compartida, no. Ese es un argumento
   práctico a favor del rollback frente al `DELETE FROM`.
3. **Partir la suite**: los de dominio en cada push, los de integración antes de mezclar.

Lo que **no** haría es cambiar a H2 para ganar velocidad. Es cambiar tiempo de CI por bugs en
producción — ver la siguiente pregunta.

### 3.2 · ¿Qué bug concreto se te escaparía usando H2 en tests y PostgreSQL en producción?

Ejemplos reales, no generalidades:

- **Sensibilidad a mayúsculas y `NULL` en el orden.** PostgreSQL ordena `NULL` al final en `ASC`;
  otros motores al principio. Una consulta paginada ordenada por una columna anulable devuelve
  resultados distintos, y tu test pasa.
- **Sintaxis específica.** `ON CONFLICT DO UPDATE`, `RETURNING`, índices parciales, `JSONB`,
  arrays, `ILIKE`. Nuestro índice parcial `WHERE status IN (...)` ni siquiera se crearía.
- **Tipos.** El caso `bpchar` de este módulo es exactamente esto: un problema que solo existe en
  PostgreSQL y que H2 nunca habría revelado.
- **Comportamiento transaccional.** PostgreSQL usa MVCC; el nivel de aislamiento por defecto y el
  modo en que detecta conflictos de escritura no coinciden con los de otros motores. Un test de
  concurrencia que pasa en H2 no dice nada sobre producción.
- **`SELECT ... FOR UPDATE SKIP LOCKED`**, imprescindible para colas de trabajo en base de datos,
  y que otros motores no implementan igual.

La regla: **la base de datos no es un detalle intercambiable**. Es la pieza con más semántica
propia de todo el sistema, y cualquier cosa no trivial depende de su motor concreto.

### 3.3 · Los CHECK constraints repiten invariantes del dominio. ¿No contradice la regla del módulo 2 de no duplicar reglas de negocio?

No, y la diferencia está en **qué se está protegiendo**.

En el módulo 2 la regla era no duplicar reglas de negocio **en los DTOs**. Un DTO es *un camino de
entrada más*: si la regla vive solo ahí, la operación que llegue por Kafka se la salta. La
duplicación es mala porque crea dos copias que divergen y **ninguna de las dos es autoritativa**.

La base de datos es otra cosa: es el **custodio final del estado**, y la aplicación no es su único
cliente. Contra estos datos escriben migraciones, jobs de backfill, herramientas de importación y
personas con `psql` a las tres de la mañana. Un CHECK es la única regla que **nadie puede
saltarse**, ni siquiera saltándose la aplicación entera.

La otra diferencia es qué pasa cuando divergen. Si un DTO y el dominio discrepan, gana el DTO en
silencio y el invariante se rompe. Si un CHECK y el dominio discrepan, la escritura **falla
ruidosamente**: te enteras al instante.

Formulado como regla: duplica hacia **adentro** (hacia el custodio del estado), nunca hacia
**afuera** (hacia los caminos de entrada).

### 3.4 · `@Enumerated(EnumType.ORDINAL)`: describe el bug exacto

`ORDINAL` guarda la **posición** del valor en la declaración del enum:

```java
enum ListingStatus { DRAFT, PUBLISHED, PAUSED, ARCHIVED }
//                     0        1        2         3
```

En la base de datos, una publicación publicada es un `1`.

Meses después, alguien añade un estado donde le parece natural:

```java
enum ListingStatus { DRAFT, PENDING_REVIEW, PUBLISHED, PAUSED, ARCHIVED }
//                     0          1             2        3         4
```

**En ese instante, todas las publicaciones que estaban `PUBLISHED` pasan a ser
`PENDING_REVIEW`.** Todo el catálogo desaparece de la tienda.

Lo que lo convierte en uno de los peores bugs posibles:

1. **No falla nada.** No hay excepción, ni error de migración, ni aviso. Los datos son válidos y
   significan otra cosa.
2. **Los tests pasan.** Escriben y leen con el mismo enum, así que son coherentes consigo mismos.
   Solo los datos preexistentes están corrompidos, y en test no hay.
3. **Es difícil de revertir.** Al descubrirlo, hay filas nuevas escritas con el ordinal nuevo
   mezcladas con las viejas. Ya no hay forma de distinguir qué significaba cada `1`.
4. **El culpable no lo sabía.** Reordenar constantes de un enum parece un cambio cosmético.

Con `STRING` la columna dice `'PUBLISHED'` y el orden del enum deja de importar. Cuesta unos bytes
por fila; es la mejor relación coste-beneficio de todo el mapeo JPA.

### 3.5 · `save()` actualiza sin llamar a ningún método. ¿Cómo se llama y qué peligro tiene?

Se llama **dirty checking** (comprobación de estado sucio). Hibernate guarda una instantánea de
cada entidad al cargarla y, al cerrar la transacción, compara campo a campo y emite un UPDATE por
cada diferencia.

Los peligros:

1. **Escrituras accidentales.** Cualquier modificación de una entidad gestionada se persiste,
   aunque fuera un cálculo temporal. El caso clásico: un método de "consulta" que normaliza un
   campo para compararlo y acaba escribiendo en la base de datos.
2. **UPDATEs invisibles en el flush.** El SQL no se emite donde escribiste el código, sino al
   cerrar la transacción. Ordenar tu código no ordena tus escrituras, y eso importa cuando hay
   locks: dos transacciones que actualizan las mismas filas en distinto orden se abrazan en un
   deadlock que no se explica leyendo el código.
3. **Coste de la comparación.** Con muchas entidades gestionadas, cada flush recorre todas. Es la
   otra cara del problema de la caché de primer nivel en procesos por lotes.
4. **Modificar dentro de un bucle de lectura.** Ese `entity.setX(...)` en medio de un informe
   escribe en producción.

Mitigaciones: `@Transactional(SUPPORTS)` o sesiones de solo lectura para las consultas, `detach()`
para lo que no debe persistirse, y trabajar con objetos de dominio inmutables —que es justo lo que
hacemos: fuera del repositorio no hay ninguna entidad gestionada a la vista.

### 3.6 · ¿Por qué `@TestTransaction` funciona en los tests de repositorio y no en los de REST?

Porque **una transacción está atada a un hilo**.

`@TestTransaction` abre una transacción antes del test y la revierte después. En un test de
repositorio, el test llama al repositorio directamente: mismo hilo, misma transacción, y el
rollback cubre todo lo escrito.

En un test de REST, RestAssured hace una **petición HTTP real**. La atiende un hilo del servidor,
que abre **su propia** transacción y la confirma al responder. Cuando el test hace rollback, está
revirtiendo su transacción —que no escribió nada—, mientras los datos del endpoint siguen
commiteados.

De ahí que los tests REST necesiten limpieza explícita.

Corolario práctico: ese mismo razonamiento explica por qué los tests con `@TestTransaction` se
pueden paralelizar y los que limpian una tabla compartida no.

### 3.7 · Optimista o pesimista para descontar stock en un flash sale

**Pesimista**, y el razonamiento es lo que se está evaluando.

El bloqueo optimista asume que los conflictos son raros: deja actuar a todos y el perdedor repite
el trabajo. Es una apuesta excelente para editar la ficha de un producto, donde dos ediciones
simultáneas son casi imposibles.

Un flash sale invierte la premisa. Diez mil personas compiten por la misma fila en el mismo
segundo. Con optimista:

- Casi todos pierden y reintentan.
- Los reintentos generan **más** contención, no menos.
- Se entra en *livelock*: mucho trabajo, muy poco progreso, y el rendimiento **cae** al aumentar la
  carga, que es el peor modo de fallo posible.

Con pesimista (`SELECT … FOR UPDATE`) las peticiones se serializan sobre esa fila. Va "lento", pero
progresa de forma constante y predecible bajo cualquier carga.

**La respuesta que distingue a un senior** es que en un flash sale de verdad no se elige entre
esas dos, sino que se saca la contención de la base de datos: una actualización atómica
condicional en una sola sentencia,

```sql
UPDATE listing SET available_stock = available_stock - 1
 WHERE id = ? AND available_stock >= 1
```

que no necesita leer antes ni mantener lock alguno entre sentencias — si afecta a 0 filas, no
había stock. Y por encima, un contador en Redis o una cola virtual que absorba el pico antes de
que llegue a la base de datos. Es el tema del módulo 6.

### 3.8 · ¿Por qué un problema N+1 no lo detecta ningún test funcional?

Porque **el resultado es correcto**. El N+1 no es un bug de comportamiento: es un bug de coste.
Devuelve exactamente los mismos datos que la versión eficiente, así que cualquier aserción sobre
la respuesta pasa.

Y tampoco se ve mirando:

- **No hay excepción ni log.** Son consultas legítimas y rápidas cada una por separado.
- **Es invisible con pocos datos.** Con 20 filas de test, 21 consultas tardan milisegundos. El
  problema aparece con 5.000 filas reales.
- **Depende del contexto, no del código.** Como comprobamos en este módulo, el mismo bucle puede
  costar 2 consultas o 40 según lo que la sesión hubiera cargado antes. Un cambio inocuo aguas
  arriba lo enciende sin que nadie toque el bucle.

Por eso hace falta un test de **presupuesto de consultas**: no asegura *qué* devuelve el código,
sino *cuánto cuesta*. Con las estadísticas de Hibernate, un N+1 pasa de ser un problema de
rendimiento invisible a un build rojo.

La variante más robusta del assert no es "exactamente 2 consultas" sino **"el número de consultas
no cambia con el volumen de datos"**, que es la firma exacta del N+1 y no se rompe al añadir una
consulta legítima.