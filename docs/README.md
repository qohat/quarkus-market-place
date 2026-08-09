# Plan de aprendizaje — Quarkus a alta escala

Construimos un **marketplace híbrido** (productos físicos + servicios reservables) desde cero,
usando Java 25 y Quarkus 3.38, hasta llegar a sistemas distribuidos a escala.

## Cómo trabajamos

1. **Primero se explica, después se escribe.** Ningún concepto nuevo aparece en el código sin
   haberlo entendido antes.
2. **Incrementos pequeños.** Como mucho una o dos clases nuevas por paso.
3. **Tú decides el diseño.** Las decisiones importantes se plantean como opciones con sus
   trade-offs; el código sale de tu elección.
4. **Preguntas de repaso** al final de cada módulo, estilo entrevista técnica.
5. **Todo se documenta aquí** y las siglas se explican en el [glosario](GLOSARIO.md).
6. **Todo se testea.** Si no hay test, no está terminado.
7. **Las preguntas de repaso se responden** en el [banco de preguntas](PREGUNTAS-RESPUESTAS.md),
   con el razonamiento completo y los trade-offs. Es el material de estudio para entrevistas.

## Documentos transversales

| Documento | Para qué |
|---|---|
| [GLOSARIO.md](GLOSARIO.md) | Siglas y términos explicados sin dar nada por sabido |
| [PREGUNTAS-RESPUESTAS.md](PREGUNTAS-RESPUESTAS.md) | Todas las preguntas de repaso, con respuesta razonada |

## Módulos

| # | Módulo | Estado | Documento |
|---|---|---|---|
| 0 | Fundamentos de Quarkus | ✅ | [00-fundamentos-quarkus.md](00-fundamentos-quarkus.md) |
| 1 | Dominio con Java moderno | ✅ | [01-dominio-y-java-moderno.md](01-dominio-y-java-moderno.md) |
| 2 | REST y ARC | ✅ | [02-rest-y-arc.md](02-rest-y-arc.md) |
| 3 | Persistencia | ✅ | [03-persistencia.md](03-persistencia.md) |
| 4 | Concurrencia: bloqueante vs reactivo vs virtual threads | ✅ | [04-concurrencia.md](04-concurrencia.md) |
| 5 | Seguridad | ✅ | [05-seguridad.md](05-seguridad.md) |
| 6 | Bounded contexts e inventario | ✅ | [06-bounded-contexts-e-inventario.md](06-bounded-contexts-e-inventario.md) |
| 7 | Mensajería: Kafka, outbox y saga | ✅ | [07-mensajeria-outbox-y-saga.md](07-mensajeria-outbox-y-saga.md) |
| 8 | Resiliencia y observabilidad | ✅ | [08-resiliencia-y-observabilidad.md](08-resiliencia-y-observabilidad.md) |
| 9 | Escala y producción | ✅ | [09-escala-y-produccion.md](09-escala-y-produccion.md) |

### Qué se cubre en cada uno

**0 · Fundamentos** — Build-time vs runtime, augmentation, extensiones, `@Recorder`, ARC, Jandex,
modelo de I/O, compilación nativa, dev mode.

**1 · Dominio** — `sealed interface`, records, value objects, ADT, pattern matching, exhaustividad.
Cero dependencias de framework, tests en milisegundos.

**2 · REST y ARC** — Quarkus REST, CDI en profundidad (scopes, productores, interceptores), DTOs,
Bean Validation, manejo de errores, OpenAPI. Los primeros `@QuarkusTest`.

**3 · Persistencia** — Hibernate ORM con Panache, PostgreSQL vía Dev Services, Flyway,
transacciones, cómo mapear tipos `sealed` a tablas, paginación.

**4 · Concurrencia** — El eje que decide la escalabilidad: event loop, Mutiny (`Uni`/`Multi`),
`@Blocking`/`@NonBlocking`, `@RunOnVirtualThread`. Con un benchmark real de los tres modelos, los
14 bugs de concurrencia más comunes y la demostración de que el cuello de botella casi nunca son
los hilos. Todo reproducible con `./scripts/bench.sh`.

**5 · Seguridad** — OIDC con Keycloak (vía Dev Services), JWT desmenuzado claim a claim, roles
vendedor/comprador y **autorización a nivel de recurso**: el fallo BOLA, número 1 del OWASP API
Security Top 10, que ninguna anotación resuelve. Con tests en tres capas.

**6 · Bounded contexts** — Separar catálogo e inventario en un monolito modular. Las tres
estrategias contra la sobreventa **medidas** con 200 compradores simultáneos, reservas con
caducidad idempotentes, y solapamiento de franjas resuelto con `EXCLUDE USING gist`: dos problemas
de concurrencia distintos, dos respuestas distintas.

**7 · Mensajería** — El problema de la escritura dual y el patrón **outbox** que lo resuelve.
Kafka con SmallRye Reactive Messaging, `SKIP LOCKED` para repartir el relay entre réplicas, y una
**saga** de compra con compensaciones. Por qué «exactly-once» no existe y las tres formas de hacer
idempotente a un consumidor.

**8 · Resiliencia y observabilidad** — Timeout, retry con jitter y circuit breaker **provocando
los fallos y midiendo los intentos reales**, no solo anotando. Cola de mensajes muertos contra el
mensaje envenenado. OpenTelemetry con la traza sobreviviendo al salto por Kafka, métricas de
cardinalidad acotada y la diferencia entre liveness y readiness.

**9 · Escala y producción** — Compilación nativa con Mandrel **medida contra la JVM**: 21× más
rápido al arrancar, 11× menos memoria y el mismo throughput. Caché con invalidación dirigida por
evento y control de admisión con cubo de fichas.

## Requisitos del entorno

- Java 25 (Temurin) — fijado en `.sdkmanrc`, ejecuta `sdk env` al entrar al proyecto
- Docker — necesario a partir del módulo 3 para Dev Services
- Gradle wrapper incluido (`./gradlew`)

## Comandos

```bash
./gradlew test          # tests
./gradlew quarkusDev    # modo desarrollo con live reload
./gradlew build         # artefacto
```
