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
| 4 | Concurrencia: bloqueante vs reactivo vs virtual threads | 🔄 siguiente | — |
| 5 | Seguridad | ⏳ | — |
| 6 | Bounded contexts e inventario | ⏳ | — |
| 7 | Mensajería: Kafka, outbox y saga | ⏳ | — |
| 8 | Resiliencia y observabilidad | ⏳ | — |
| 9 | Escala y producción | ⏳ | — |

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
`@Blocking`/`@NonBlocking`, `@RunOnVirtualThread`. Con un benchmark real de los tres modelos.

**5 · Seguridad** — OIDC con Keycloak (vía Dev Services), JWT, roles vendedor/comprador,
autorización a nivel de recurso.

**6 · Bounded contexts** — Separar catálogo, inventario, órdenes y pagos. Reserva de stock sin
sobreventa y reserva de franjas horarias: dos problemas de concurrencia distintos.

**7 · Mensajería** — SmallRye Reactive Messaging, Kafka, patrón **outbox** transaccional, **saga**
de pago con compensaciones, idempotencia frente a mensajes duplicados.

**8 · Resiliencia y observabilidad** — Circuit breaker, retry, bulkhead, timeout. OpenTelemetry,
tracing distribuido, Micrometer, health checks.

**9 · Escala y producción** — Caché, rate limiting, compilación nativa con Mandrel, contenedores,
Kubernetes, autoescalado y prueba de carga.

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
