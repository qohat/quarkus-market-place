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
