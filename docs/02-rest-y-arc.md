# Módulo 2 — REST y ARC

> Siglas de este módulo: **CDI**, **ARC**, **JAX-RS**, **DTO**, **RFC 7807**, **OpenAPI**.
> Ver [GLOSARIO.md](GLOSARIO.md). Las preguntas de repaso están respondidas en
> [PREGUNTAS-RESPUESTAS.md](PREGUNTAS-RESPUESTAS.md).

Aquí entra Quarkus de verdad. El módulo 1 fue Java puro; este es el framework.

## 1. CDI y ARC: la inyección de dependencias

**CDI** es la especificación estándar de Jakarta para inyección de dependencias. **ARC** es la
implementación de Quarkus, que la resuelve en build time. Viniendo de Spring, el mapa es directo:

| Spring | CDI / Quarkus | Nota |
|---|---|---|
| `@Component` / `@Service` / `@Repository` | `@ApplicationScoped` | En CDI el **scope define el bean**. No hay tres anotaciones decorativas. |
| `@Autowired` | `@Inject`, o por constructor | |
| `@Bean` en `@Configuration` | `@Produces` | Para tipos que no controlas. |
| `@Qualifier` | `@Qualifier` | El de CDI es tipado, no un string. |
| `@Primary` | `@DefaultBean` / `@Alternative` + `@Priority` | |
| `@Value("${…}")` | `@ConfigProperty` | |
| `applicationContext.getBean()` | ❌ no existe | Ver pregunta 0.1 |

En CDI **no hay `@Service` ni `@Repository`**: en Spring son alias decorativos de `@Component`.
Aquí la anotación declara el **ciclo de vida**, y eso tiene consecuencias reales.

### Los scopes

| Anotación | Instancias | Proxy | Cuándo |
|---|---|---|---|
| `@ApplicationScoped` | Una, creada **perezosamente** | ✅ | **El 90% de los casos** |
| `@Singleton` | Una, sin pereza | ❌ | Micro-optimización |
| `@RequestScoped` | Una por petición HTTP | ✅ | Usuario autenticado, correlation id |
| `@Dependent` | Una por punto de inyección | ❌ | El default si no pones nada — suele ser un error |
| `@SessionScoped` | Una por sesión HTTP | ✅ | **Evítalo**: estado en el servidor = sticky sessions = no escalas |

### El *client proxy*

Al inyectar un `@ApplicationScoped`, ARC **no te da el objeto**, sino un `_ClientProxy` generado
que en cada llamada resuelve la instancia real del contexto activo:

```
tu código ──llama──> MiServicio_ClientProxy ──resuelve──> MiServicio (real)
```

Habilita tres cosas: inicialización perezosa real, romper ciclos de dependencias, y —lo
importante— inyectar un bean de vida **corta** dentro de uno de vida **larga**. Sin proxy, un
`@RequestScoped` inyectado en un `@ApplicationScoped` se quedaría con la instancia de la primera
petición para siempre.

El precio: la clase no puede ser `final` ni tener métodos `private` que esperes interceptar.

### Inyección por constructor

```java
@ApplicationScoped
public class ListingCatalog {
    private final ListingRepository repository;

    ListingCatalog(ListingRepository repository) {   // sin @Inject: es el único constructor
        this.repository = repository;
    }
}
```

Campo `final` → dependencia obligatoria y objeto inmutable. Y el efecto práctico más valioso:
`new ListingCatalog(new InMemoryListingRepository())` en un test, **sin contenedor y sin mocks**.

## 2. Puertos y adaptadores

```
catalog/
├── domain/                    ← no depende de nada
│   ├── Listing, Money, …
│   ├── ListingRepository        (PUERTO: interfaz, la define el dominio)
│   └── ListingNotFoundException
├── application/
│   └── ListingCatalog           (casos de uso: orquesta, no conoce HTTP)
└── infrastructure/
    ├── InMemoryListingRepository (ADAPTADOR de salida)
    └── rest/                     (ADAPTADOR de entrada)
        ├── ListingResource
        ├── DTOs
        └── ExceptionMappers
```

La regla: **las dependencias apuntan hacia dentro**. El dominio declara lo que necesita
(`ListingRepository`) y la infraestructura se adapta. Por eso en el módulo 3 sustituiremos el
`HashMap` por PostgreSQL sin tocar una línea del dominio.

Es también la razón por la que `ListingNotFoundException` no sabe nada de HTTP: el dominio dice
"esto no existe" y **cada adaptador** decide qué significa. En REST es un 404; el consumidor de
Kafka del módulo 7 decidirá otra cosa.

## 3. Quarkus REST

### Detalle importante: los recursos son singleton

En JAX-RS clásico, cada petición instancia un recurso nuevo. **En Quarkus REST son singleton por
defecto**, lo que ahorra una asignación por petición. La contrapartida es directa: **nada de
estado mutable en campos**.

Se puede verificar en el bytecode generado:

```
ListingCatalog_Bean + ListingCatalog_ClientProxy   ← @ApplicationScoped
ListingResource_Bean                                ← sin proxy = singleton
```

### Diseño de rutas elegido

```
POST /listings/products          201 + Location
POST /listings/services          201 + Location
GET  /listings                   catálogo público
GET  /listings?seller={id}       panel del vendedor (incluye borradores)
GET  /listings/{id}
GET  /listings/{id}/availability?quantity=
POST /listings/{id}/publish | pause | archive
```

**Sub-recursos separados** para crear, porque producto y servicio tienen cuerpos genuinamente
distintos, y un solo endpoint obligaría a validación condicional.

**Acciones con nombre** para las transiciones, en vez de `PATCH {"status": "PUBLISHED"}`. Menos
ortodoxo en REST, mucho más práctico: cada transición se autoriza y se audita por separado en el
módulo 5, en lugar de ramificar sobre el cuerpo dentro de un único método. Es lo que hacen
Stripe y GitHub.

### La trampa del `@Consumes` a nivel de clase

```java
@Path("/listings")
@Produces(MediaType.APPLICATION_JSON)
// @Consumes AQUÍ NO
```

Puesto en la clase, se aplicaría también a `publish`, `pause` y `archive`, que son `POST` **sin
cuerpo** y por tanto llegan sin `Content-Type`. JAX-RS los rechazaría con **415 Unsupported Media
Type**. `@Consumes` describe lo que acepta un método concreto, no una política de clase.

*(Este bug apareció de verdad al escribir los tests. Seis fallaron.)*

## 4. DTOs y el contrato JSON

### Por qué no serializar el dominio

1. El JSON quedaría acoplado al modelo interno: renombrar un campo rompe a los clientes.
2. El dominio se llenaría de anotaciones de Jackson.
3. Un DTO de **entrada** tiene campos distintos de uno de salida: `CreateProductRequest` no lleva
   `id`, ni `status`, ni `sellerId` — si el cliente pudiera elegir el vendedor, **cualquiera
   publicaría en nombre de otro**.

### El polimorfismo, con discriminador plano

```json
{ "type": "PRODUCT", "title": "Teclado", "availableUnits": 40 }

{ "type": "SERVICE", "title": "Clase de guitarra", "availableUnits": 1,
  "service": { "slotMinutes": 60, "timeZone": "Europe/Madrid" } }
```

`@JsonInclude(NON_NULL)` omite el bloque `service` en productos, en vez de emitir `"service": null`.

Y el mapeo es donde el `sealed` del módulo 1 se paga solo:

```java
public static ListingResponse from(Listing listing) {
    return switch (listing) {
        case ProductListing product -> …;
        case ServiceListing service -> …;
    };   // exhaustivo, sin default
}
```

Añadir un tercer tipo de listing **rompe la compilación aquí**, obligando a decidir cómo se
representa — en vez de emitir un objeto incompleto al cliente.

### `Money` como string

```json
"price": { "amount": "25.00", "currency": "EUR" }
```

JSON no tiene tipo decimal. `JSON.parse` en el navegador convierte los números a `double`
IEEE-754: `25.00` se lee como `25` y se pierde la escala. Como string, llega intacto. Ver
pregunta 2.4 para cuándo un número sería defendible.

## 5. Bean Validation

**Dos validaciones distintas que no deben mezclarse:**

| | Valida | Dónde | Ejemplo |
|---|---|---|---|
| Bean Validation | la **forma** del payload | DTOs, en el borde | `title` no vacío, `currency` = `[A-Z]{3}` |
| El dominio | los **invariantes de negocio** | records de dominio | precio positivo, no salir de estado terminal |

Duplicar reglas de negocio en los DTOs las condena a divergir, y además se saltarían por completo
cuando la operación llegue por Kafka.

### La ganancia real: todos los errores de una vez

Una validación a mano aborta en el primer error. Bean Validation recorre el objeto entero:

```json
{ "detail": "The request has 4 validation error(s)",
  "violations": [
    { "field": "amount",   "message": "amount must be a decimal number, e.g. \"25.00\"" },
    { "field": "currency", "message": "currency must be a 3-letter ISO 4217 code…" },
    { "field": "stock",    "message": "stock cannot be negative" },
    { "field": "title",    "message": "title is required" }
  ] }
```

### Lo que Bean Validation NO puede comprobar

`@Pattern("[A-Z]{3}")` acepta `"XYZ"` sin pestañear: sintácticamente es impecable. Que exista en
ISO 4217 solo lo sabe `Currency.getInstance`, al construirla. Igual con las zonas IANA y con la
escala decimal de la moneda.

**Regla:** valida declarativamente lo que es sintaxis, y deja que falle donde vive el conocimiento.
Replicar el catálogo ISO 4217 en anotaciones sería absurdo y quedaría desactualizado.

### Mensajes explícitos

Los mensajes por defecto de Hibernate Validator se traducen según el **locale del servidor**: la
respuesta de tu API cambiaría según dónde esté desplegada, y los tests fallarían al cambiar de
máquina. Por eso están escritos a mano.

**Regla que adoptamos:** los mensajes de excepción van en inglés, porque forman parte del
contrato de la API. Comentarios y documentación, en español.

## 6. Errores: RFC 7807

Todos los errores comparten formato, servidos como `application/problem+json`:

```json
{
  "type":     "https://marketplace.local/problems/listing-not-found",
  "title":    "Listing not found",
  "status":   404,
  "detail":   "No listing exists with id 0000…",
  "instance": "/listings/0000…"
}
```

`type` es una URI **estable** y es el campo sobre el que un cliente debe ramificar; el `title`
puede reescribirse sin previo aviso.

| Código | `type` | Cuándo |
|---|---|---|
| 404 | `listing-not-found` | La publicación no existe |
| 409 | `invalid-state-transition` | Publicar algo archivado |
| 400 | `invalid-request` | Invariante de dominio roto |
| 400 | `validation-failed` | Bean Validation, con detalle por campo |

**409 y no 400** para las transiciones ilegales: la distinción es útil para el cliente. Un 400
dice "arregla la petición"; un 409 dice "la petición está bien, es el estado del servidor el que
no encaja" — y a veces basta con recargar y reintentar.

Quarkus trae su propio mapper para las violaciones de Bean Validation, pero con otro formato.
Registrar el nuestro lo sustituye, para que el cliente no tenga que entender dos formatos según
qué falle.

> **Deuda consciente:** los mappers capturan `IllegalArgumentException` e `IllegalStateException`,
> que son tipos del JDK. Un bug interno podría presentarse como 4xx y desaparecer de las métricas
> de error. Se mitiga con log en WARN, pero la solución correcta son excepciones de dominio
> propias. Ver pregunta 2.8.

## 7. OpenAPI

`quarkus-smallrye-openapi` genera el documento en build time a partir de los `@Path` y los DTOs.
**Y traduce automáticamente las anotaciones de Bean Validation al esquema:**

```json
"CreateProductRequest": {
  "required": ["title", "amount", "currency"],
  "properties": {
    "title":    { "type": "string", "pattern": "\\S", "maxLength": 200 },
    "currency": { "type": "string", "pattern": "[A-Z]{3}" },
    "stock":    { "type": "integer", "minimum": 0 }
  }
}
```

Una sola declaración **valida y documenta**. Disponible en:

- `/q/openapi` — el documento (YAML por defecto, `?format=json` para JSON)
- `/q/swagger-ui` — la interfaz interactiva

## 8. Dev mode

```bash
./gradlew quarkusDev
```

Arranque medido: **1,2 s** en dev (que carga instrumentación extra).

**Live reload observado en directo:** al editar un `.java`, la siguiente petición dispara

```
Restarting quarkus due to changes in ProductListing.class
```

y responde ya con el código nuevo, en ~0,4 s.

> **Detalle que conviene entender:** dice *Restarting*. El live reload **reinicia la aplicación**,
> no hace hot-swap del bytecode en vivo. Consecuencia inmediata que vimos en la prueba: el
> `@ApplicationScoped` con el `ConcurrentHashMap` se reconstruyó **vacío** y los listings creados
> desaparecieron. Es precisamente lo que motiva el módulo 3.

Otras herramientas: `/q/dev-ui` (beans, config, endpoints), testing continuo con `r`.

## 9. Configuración

Tres cosas de SmallRye Config:

1. **Perfiles**: `%dev.`, `%test.`, `%prod.` como prefijo.
2. **Prioridad**: sistema (`-D`) > variables de entorno > `.env` > `application.properties`.
   El nombre se traduce: `quarkus.http.port` → `QUARKUS_HTTP_PORT`.
3. **Build time vs runtime**: algunas propiedades quedan congeladas en el artefacto. Marcadas con
   🔒 en la documentación. Ver pregunta 0.2.

---

## Preguntas de repaso

Respondidas en [PREGUNTAS-RESPUESTAS.md](PREGUNTAS-RESPUESTAS.md), sección Módulo 2:

- 2.1 · ¿Qué le impide a Spring generar un invoker con llamada directa?
- 2.2 · `@ApplicationScoped` sin estado mutable: ¿por qué importa?
- 2.3 · `List<Listing>` sellado: ¿qué pasa al serializarlo?
- 2.4 · `amount` como String vs número
- 2.5 · `from()` estático en el DTO vs `toResponse()` en el dominio
- 2.6 · Un contador en un recurso singleton: ¿por qué falla exactamente?
- 2.7 · ¿Qué gana el cliente con la cabecera `Location`?
- 2.8 · El riesgo de mapear `IllegalArgumentException` a 400
- 2.9 · ¿Por qué el live reload borró los datos en memoria?

## Estado del proyecto

- API REST completa del catálogo, con OpenAPI y errores RFC 7807
- **76 tests** en verde: dominio puro (rápidos) + `@QuarkusTest` (integración real por HTTP)
- Scaffolding `org.acme` eliminado
- Siguiente: persistencia con Hibernate ORM + Panache y PostgreSQL vía Dev Services
