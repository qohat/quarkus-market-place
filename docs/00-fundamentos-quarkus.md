# Módulo 0 — Cómo funciona Quarkus realmente

> Siglas de este módulo: **CDI**, **ARC**, **AOT**, **JIT**, **JVM**, **RSS**, **Jandex**.
> Todas están explicadas en [GLOSARIO.md](GLOSARIO.md).

## 1. El problema que Quarkus vino a resolver

Un framework JVM clásico (Spring Boot, Jakarta EE) hace este trabajo **en cada arranque, en cada
instancia, en cada pod**:

1. Escanear el classpath buscando anotaciones — usando *reflection*.
2. Construir un metamodelo en memoria: beans, endpoints, entidades, interceptores.
3. Parsear la configuración y generar proxies dinámicos.
4. Cablear el grafo de dependencias.

Eso cuesta segundos de arranque y cientos de megas de RAM. Y hay algo peor: depende de reflection y
de carga dinámica de clases, que es **exactamente** lo que un compilador nativo AOT no puede
resolver.

Cuando tu unidad de despliegue era un servidor de aplicaciones que arrancaba una vez al mes, esto
daba igual. Cuando es un pod que escala de 3 a 300 réplicas en un pico de tráfico y vuelve a bajar,
deja de dar igual.

## 2. La tesis: mover trabajo de *runtime* a *build time*

Quarkus mueve los pasos 1–4 al momento de compilar. La fase se llama **augmentation**.

```
   Framework clásico              Quarkus
   ─────────────────              ───────
   build:  compilar               build:  compilar
                                          + escanear (Jandex)
                                          + resolver inyección
                                          + generar bytecode cableado
   ──────────────────────────────────────────────────────────────────
   arranque: escanear             arranque: ejecutar el bytecode ya generado
             reflection
             construir metamodelo
             cablear beans
             ~2-5 s                        ~0,05 s
```

> **La consecuencia que hay que interiorizar:** en Quarkus, "hacer algo en el arranque" es una
> decisión de diseño cara. El framework asume que todo lo que se puede saber en build time, se sabe
> en build time.

## 3. Las piezas que lo implementan

### Extensiones: dos módulos, no uno

Toda extensión (`quarkus-hibernate-orm`, `quarkus-kafka-client`…) se parte en dos:

- **`deployment`** — solo existe en build time, **nunca llega al artefacto final**. Contiene
  métodos anotados con `@BuildStep` que consumen y producen `BuildItem`s. El motor de Quarkus
  resuelve un grafo de dependencias entre todos los build steps de todas las extensiones.
- **`runtime`** — lo que sí se empaqueta. Contiene los `@Recorder`s.

### El truco del `@Recorder`

Un `@Recorder` es una clase cuyos métodos, cuando los llamas desde un `@BuildStep`, **no se
ejecutan**. Se *graban*: Quarkus genera el bytecode equivalente a esa llamada, y ese bytecode es lo
que se ejecuta al arrancar la aplicación.

Es decir: la lógica de "configurar el datasource leyendo estas propiedades" se resuelve en build
time, y lo que queda en el binario es el código mínimo de "crea este pool con estos valores".

### Jandex: escanear sin reflection

Quarkus no recorre el classpath con reflection. Lee un **índice de anotaciones** precomputado
(`META-INF/jandex.idx`). Por eso existe la propiedad `quarkus.index-dependency.*`: un jar de
terceros sin índice es sencillamente invisible para el framework, y sus `@Entity` o `@Path` no se
descubren.

### ARC: CDI resuelto en compilación

**ARC** es la implementación de CDI de Quarkus. En build time resuelve toda la inyección y genera
clases Java reales:

```
MiServicio          →  MiServicio_Bean          (fábrica del bean)
                       MiServicio_ClientProxy   (proxy de scope)
                       MiServicio_Subclass      (si hay interceptores)
```

Dos efectos prácticos que conviene tener presentes desde el primer día:

1. **Hay restricciones frente a CDI clásico.** No existe la resolución dinámica arbitraria de
   beans en runtime, porque el grafo ya está cerrado.
2. **ARC hace eliminación de código muerto.** Un bean que nadie inyecta se borra del binario. Si lo
   resuelves de forma dinámica, ARC no lo ve y lo elimina — para eso está `@Unremovable`.

## 4. El modelo de I/O (el tema que decide la escalabilidad)

Todo Quarkus corre sobre **Vert.x / Netty**: un pool pequeño de *event loops*, 2 por núcleo de CPU.
Un event loop atiende miles de conexiones porque nunca se queda esperando.

Quarkus elige dónde ejecutar tu método según su firma:

| Estilo | El método devuelve | Dónde se ejecuta | Cuándo usarlo |
|---|---|---|---|
| Imperativo | `String`, `Order`, `List<T>`… | worker thread pool | Código bloqueante: JDBC, llamadas HTTP síncronas |
| Reactivo | `Uni<T>` / `Multi<T>` | **event loop** | Máximo throughput. **Prohibido bloquear.** |
| Virtual threads | cualquiera, con `@RunOnVirtualThread` | virtual thread | Código imperativo con coste cercano al reactivo (Java 21+) |

Las anotaciones `@Blocking` y `@NonBlocking` permiten forzar la decisión.

> Si bloqueas un event loop (una consulta JDBC, un `Thread.sleep`, un `.await()`), paras todas las
> conexiones que ese hilo atendía. Quarkus incluso te avisa por log cuando lo detecta.

Este eje es el tema del **módulo 3**, y lo mediremos con números reales.

## 5. Compilación nativa

Con GraalVM/Mandrel, Quarkus compila a un ejecutable nativo bajo la hipótesis de **closed world**:

|  | JVM | Nativo |
|---|---|---|
| Arranque | ~0,8 s | ~0,015 s |
| RSS | ~120 MB | ~30 MB |
| Pico de rendimiento | mayor (el JIT optimiza en caliente) | menor |
| Tiempo de build | segundos | minutos |

Funciona precisamente porque las extensiones ya registraron en build time toda la reflection, los
recursos y los proxies que hacen falta. Es la recompensa de la tesis del punto 2.

## 6. Herramientas de desarrollo

```bash
./gradlew quarkusDev
```

- **Live reload**: al llegar una petición, si el código cambió, recompila y recarga en caliente.
- **Dev Services**: declaras `quarkus-jdbc-postgresql` sin dar URL → Quarkus arranca un contenedor
  Postgres solo, y lo apaga al salir. Igual con Kafka, Redis, Keycloak…
- **Testing continuo**: tecla `r`, los tests corren al guardar.
- **Dev UI**: <http://localhost:8080/q/dev-ui>

## 7. Configuración

Quarkus usa **SmallRye Config** (implementación de MicroProfile Config). Puntos clave:

- Perfiles con prefijo: `%dev.quarkus.log.level=DEBUG`, `%test.…`, `%prod.…`
- Fuentes con prioridad (*ordinal*): variables de entorno > `.env` > `application.properties`
- `@ConfigMapping` sobre **interfaces**, con tipos seguros y validación
- **Build-time config vs runtime config**: hay propiedades que quedan congeladas en el artefacto y
  no se pueden cambiar con una env var en producción. La documentación de cada propiedad lo indica
  con un candado 🔒.

---

## Preguntas de repaso

1. ¿Por qué ARC necesita la anotación `@Unremovable`? ¿Qué patrón de Spring deja de ser posible?
2. Si una propiedad es *build-time config*, ¿qué implica para tu pipeline de despliegue?
3. Tienes un endpoint que hace una consulta JDBC. ¿En qué hilo debe correr y por qué?
4. ¿Por qué la compilación nativa rompe con reflection no declarada?

## Estado del proyecto al terminar el módulo

- Java 25 (Temurin) fijado vía toolchain de Gradle y `.sdkmanrc`
- Quarkus 3.38.1, Gradle 9.6
- Extensiones: `quarkus-arc`, `quarkus-rest`
