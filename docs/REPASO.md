# Repaso — sesión 1

Estado al cerrar la primera sesión, y plan para la siguiente.

```
Módulos 0-3 completos  ·  106 tests en verde  ·  17 commits
Java 25 (Temurin)  ·  Quarkus 3.38.1  ·  Gradle 9.6  ·  PostgreSQL 18
```

---

# Parte 1 · Lo que construimos

## El proyecto

Un marketplace híbrido: **productos físicos** (recurso escaso = un contador de stock) y
**servicios reservables** (recurso escaso = el tiempo de calendario). Esa dualidad es la que
genera los dos modelos de concurrencia distintos que exploraremos.

```
src/main/java/com/marketplace/
├── shared/
│   ├── domain/              Money · SellerId · Page · PageRequest
│   └── infrastructure/rest/ ProblemDetail · PageResponse · ExceptionMappers
└── catalog/
    ├── domain/              Listing (sealed) · ProductListing · ServiceListing
    │                        ListingStatus · FulfillmentCheck (ADT) · Listings
    │                        ListingRepository (PUERTO) · ListingNotFoundException
    ├── application/         ListingCatalog (casos de uso, @Transactional)
    └── infrastructure/
        ├── persistence/     ListingEntity + subclases · PanacheListingRepository
        └── rest/            ListingResource · DTOs · mapper de 404
```

**La regla que lo gobierna todo:** las dependencias apuntan hacia dentro. El dominio no tiene ni
una anotación de Hibernate, de Jackson ni de JAX-RS.

## API actual

```
GET  /listings?page=&size=&seller=      catálogo paginado
GET  /listings/{id}
GET  /listings/{id}/availability?quantity=
POST /listings/products                 201 + Location
POST /listings/services                 201 + Location
POST /listings/{id}/publish|pause|archive

GET  /q/openapi     /q/swagger-ui     /q/dev-ui     /q/health
```

---

# Parte 2 · Las ideas, módulo a módulo

## Módulo 0 — Cómo funciona Quarkus

**La tesis:** mover a *build time* todo lo que otros frameworks hacen al arrancar — escanear
anotaciones, resolver inyección, generar proxies. La fase se llama **augmentation**.

- **Extensiones** = 2 módulos: `deployment` (solo build) + `runtime` (lo que se empaqueta).
- **`@Recorder`**: sus métodos no se ejecutan, se *graban* como bytecode que corre al arrancar.
- **Jandex**: índice de anotaciones offline, en vez de escanear con reflection.
- **ARC**: CDI resuelto en compilación. Genera `_Bean`, `_ClientProxy`, `_Subclass` reales.
- **Modelo de I/O**: event loops de Vert.x. Bloquear uno es el pecado capital.
- **Native**: closed world, arranque ~15 ms, RSS ~30 MB.

**Lo comprobamos en el bytecode real** (`./scripts/inspeccionar-arc.sh`): el invoker REST hace
`invokevirtual` directo, sin reflection, y ARC borró un bean que nadie inyectaba.

## Módulo 1 — Dominio con Java moderno

- **`sealed interface` + records** = ADT. Exhaustividad verificada por el compilador.
- **Compact constructor**: el único punto por el que pasan *todas* las construcciones. Ahí se
  fuerzan los invariantes.
- **El bug de `BigDecimal.equals`**: compara la escala, así que `34.5 != 34.50`. `Money` normaliza
  la escala para que no pase.
- **`RoundingMode.UNNECESSARY`**: fallar antes que redondear dinero en silencio.
- **ADT en lugar de booleano**: `FulfillmentCheck` lleva el motivo *con sus datos*.
- **Record patterns**: `case Fulfillable(var total) -> ...`

## Módulo 2 — REST y ARC

- **Scopes**: `@ApplicationScoped` (con proxy) para el 90%. Nunca `@SessionScoped` si quieres
  escalar horizontalmente.
- **Client proxy**: permite inyectar un bean de vida corta dentro de uno de vida larga.
- **Quarkus REST hace los recursos singleton** → nada de estado mutable en campos.
- **DTOs separados**: los de entrada no llevan `sellerId` — si el cliente lo eligiera, cualquiera
  publicaría en nombre de otro.
- **`Money` como string en JSON**: `JSON.parse` convertiría el número a `double` y `25.00` se
  leería `25`.
- **Validación en dos capas**: Bean Validation valida la *forma*; el dominio, los *invariantes*.
  `@Pattern("[A-Z]{3}")` acepta `"XYZ"` sin problema — que exista en ISO 4217 solo lo sabe
  `Currency`.
- **RFC 7807**: todos los errores con la misma forma, en `application/problem+json`.

## Módulo 3 — Persistencia

- **Dev Services**: una línea de configuración levanta PostgreSQL. Los tests corren contra el
  mismo motor que producción.
- **Flyway manda**, Hibernate solo `validate`. El esquema es el contrato.
- **Entidades separadas del dominio**: JPA exige constructor vacío y mutabilidad, incompatibles
  con records inmutables.
- **`EnumType.STRING`, nunca `ORDINAL`**: reordenar el enum cambiaría el significado de todas las
  filas existentes.
- **`@Transactional` en el caso de uso**, no en el repositorio: la transacción delimita una unidad
  de negocio.
- **Paginación**: el orden necesita desempate (`ORDER BY title, id`) o las páginas duplican y
  pierden elementos.
- **`@Version`** contra el lost update → 409, no 500.
- **Presupuesto de consultas** contra el N+1: la aserción robusta es "el número de consultas no
  cambia con el volumen de datos".

---

# Parte 3 · Los fallos que más enseñaron

Vale la pena repasarlos: en una entrevista, saber por qué falla algo vale más que saber la API.

| Fallo | Lección |
|---|---|
| `415 Unsupported Media Type` en 6 tests | `@Consumes` a nivel de clase se aplica también a los `POST` sin cuerpo |
| `bpchar` vs `char(3)` | `CHAR` en PostgreSQL rellena con espacios. Casi nunca es lo que quieres |
| `does not override count()` | Java no elige entre un `abstract` y un `default` de interfaces sin relación |
| `normally automatically overridden` | Declarar un método Panache te saca de su generación en build time |
| interceptor en clase interna | `@TestTransaction` y `@Nested` de JUnit son incompatibles |
| **El N+1 que no ocurrió** | La caché de sesión lo absorbió. **El mismo bucle cuesta 2 o 40 consultas según lo que la sesión cargara antes** |
| El bean que desapareció | ARC borra lo que nadie inyecta. De ahí `@Unremovable` |
| Live reload borró los datos | *Restarting*, no hot-swap: el `@ApplicationScoped` se recrea vacío |

---

# Parte 4 · Material de estudio

| Documento | Contenido |
|---|---|
| [00-fundamentos-quarkus.md](00-fundamentos-quarkus.md) | El modelo mental de Quarkus |
| [01-dominio-y-java-moderno.md](01-dominio-y-java-moderno.md) | sealed, records, ADTs, value objects |
| [02-rest-y-arc.md](02-rest-y-arc.md) | CDI, Quarkus REST, validación, RFC 7807, OpenAPI |
| [03-persistencia.md](03-persistencia.md) | Dev Services, Flyway, Panache, paginación, N+1 |
| **[PREGUNTAS-RESPUESTAS.md](PREGUNTAS-RESPUESTAS.md)** | **25 preguntas con respuesta razonada. El material clave para entrevistas** |
| [GLOSARIO.md](GLOSARIO.md) | Todas las siglas explicadas desde cero |

### Las cinco preguntas que más se preguntan en entrevistas

1. **0.1** — ¿Por qué `getBean()` de Spring no existe en Quarkus?
2. **2.4** — `amount` como string o número en JSON, y cuándo cada uno
3. **3.4** — El bug exacto de `EnumType.ORDINAL`
4. **3.7** — Flash sale: ¿optimista o pesimista? *(la buena respuesta no es ninguna)*
5. **3.8** — ¿Por qué ningún test funcional detecta un N+1?

---

# Parte 5 · Plan para la sesión 2

**Objetivo: que escribas tú el código.** Los ejercicios están pensados para que el compilador y
los tests te vayan guiando, así que no hace falta que recuerdes la sintaxis de memoria.

## Calentamiento — 30 min escribiendo Java

### Ejercicio 1 · Añade un tercer tipo de publicación *(el mejor para reengancharse)*

Crea `DigitalListing` — un ebook o una licencia: no tiene stock físico ni calendario, sino
descargas ilimitadas y un tamaño de fichero.

```java
public record DigitalListing(
        ListingId id, SellerId sellerId, String title, Money price,
        ListingStatus status, long fileSizeBytes, String downloadUrl
) implements Listing { ... }
```

**Lo interesante es lo que pasa al añadirlo:** el build se rompe en *todos* los sitios que hay que
tocar, porque los `switch` sobre `Listing` dejan de ser exhaustivos. Vas arreglando lo que el
compilador señala y, al terminar, la funcionalidad está completa.

Sitios que se romperán (no mires hasta intentarlo):
`Listings.check` · `ListingResponse.from` · `ListingEntity.fromDomain`

Y los que **no** se romperán pero hay que tocar: la migración Flyway (`V3`), el `CHECK` del
discriminador, y una nueva entidad.

> Esto es exactamente el valor de `sealed` que discutimos en el módulo 1, vivido en primera
> persona.

### Ejercicio 2 · `Money.allocate(int partes)` *(lógica pura, muy entrevistable)*

Reparte un importe en N partes **sin perder ni un céntimo**.

```java
Money.of("10.00","EUR").allocate(3)
// -> [3.34, 3.33, 3.33]   suman exactamente 10.00
```

Dividir y redondear pierde o inventa dinero. El problema clásico de Fowler; la solución cabe en
diez líneas. Test primero: la suma de las partes debe ser siempre igual al original, para
cualquier importe y cualquier número de partes.

### Ejercicio 3 · Un endpoint tuyo, de principio a fin

`GET /listings/{id}/similar` — otras publicaciones del mismo vendedor, paginadas, excluyendo la
propia. Tocas: puerto, adaptador Panache, caso de uso, recurso REST, y un test de presupuesto de
consultas para asegurarte de no meter un N+1.

## Después — Módulo 4: el eje de concurrencia

El contenido más relevante para "alta escala". Lo mediremos, no lo teorizaremos:

| | Modelo | Cómo se escribe |
|---|---|---|
| 1 | **Worker thread** (lo actual) | Imperativo, JDBC bloqueante |
| 2 | **Virtual threads** | Idéntico, más `@RunOnVirtualThread` |
| 3 | **Reactivo** | `Uni`/`Multi` + cliente reactivo de PostgreSQL |

Comparando bajo carga: throughput, latencia p50/p99, RSS y número de hilos vivos.

**La hipótesis que quiero que veas confirmada o refutada con datos:** que el cuello de botella
real no son los hilos sino **el pool de conexiones**, y que por tanto los virtual threads no
regalan capacidad de base de datos.

Quedaron dos decisiones abiertas:
- Benchmark sintético (aísla el modelo de concurrencia) vs con base de datos real (realista) vs
  ambos para contrastar.
- Herramienta de carga: instalar `hey`/`wrk` con brew, escribir el generador en Java dentro del
  repo, o k6.

## Y después

**5** Seguridad (OIDC/Keycloak) · **6** Bounded contexts e inventario, con la sobreventa ·
**7** Kafka, outbox y saga · **8** Resiliencia y observabilidad · **9** Native, Kubernetes y escala

---

## Para arrancar mañana

```bash
cd ~/Projects/Me/quarkus-market-place
sdk env                    # fija Java 25 (lee .sdkmanrc)
./gradlew test             # 106 en verde, tarda ~1 min: levanta PostgreSQL
./gradlew quarkusDev       # http://localhost:8080/q/dev-ui
```

Docker tiene que estar arrancado: Dev Services lo necesita.
