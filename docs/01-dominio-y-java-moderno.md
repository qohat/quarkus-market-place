# Módulo 1 — El dominio, con Java moderno

> Siglas de este módulo: **ADT**, **Value Object**, **bounded context**, **record pattern**.
> Ver [GLOSARIO.md](GLOSARIO.md).

En este módulo **no hay ni una línea de Quarkus**. Es deliberado: el núcleo del negocio no debe
depender del framework. Todo lo que sigue compila y se testea sin arrancar ningún contenedor de
inyección, y los tests tardan milisegundos.

## 1. Qué construimos

Un marketplace donde un vendedor publica dos cosas muy distintas:

- **Productos físicos** — el recurso escaso es un **contador de stock**.
- **Servicios reservables** — el recurso escaso es el **tiempo**, franjas de calendario.

Esa diferencia no es cosmética: genera dos modelos de concurrencia completamente distintos, y por
eso es un caso de estudio tan bueno.

## 2. Estructura de paquetes

Organizados por **bounded context** desde el primer día, no por capa técnica:

```
com.marketplace
├── shared/domain/          Value objects compartidos entre contextos
│   ├── Money.java
│   └── SellerId.java
└── catalog/domain/         El contexto "catálogo"
    ├── Listing.java             (sealed interface)
    ├── ProductListing.java      (record)
    ├── ServiceListing.java      (record)
    ├── ListingId.java
    ├── ListingStatus.java       (enum con comportamiento)
    ├── FulfillmentCheck.java    (ADT de resultado)
    └── Listings.java            (operaciones de dominio)
```

**Por qué no `controller/ service/ repository/`:** esa estructura agrupa por lo que el código *es*,
no por lo que *hace*. Cuando en el módulo 5 partamos el sistema en contextos independientes, esta
disposición ya está preparada. Los paquetes por capa obligan a tocar tres sitios para cualquier
cambio de negocio.

## 3. `Money`: por qué un `BigDecimal` suelto no basta

```java
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {
    public Money {
        Objects.requireNonNull(amount, "amount no puede ser null");
        Objects.requireNonNull(currency, "currency no puede ser null");
        amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
    }
    ...
}
```

### El *compact constructor*

Ese constructor sin paréntesis es un **compact constructor**: se ejecuta antes de asignar los
campos finales del record, y puede **reasignar los parámetros** para normalizar el valor. Es el
único sitio donde un record puede validar e higienizar sus datos.

### El bug sutil que evita la normalización de escala

`BigDecimal.equals()` **compara también la escala**:

```java
new BigDecimal("34.5").equals(new BigDecimal("34.50"));  // false
```

Un `record` genera su `equals` delegando en el de cada componente. Sin normalizar, `Money` heredaría
ese comportamiento y dos importes idénticos serían distintos según cómo se hubieran escrito. Como
`Money` se va a usar como clave de mapas, en asserts y en comparaciones de negocio, sería una fuente
inagotable de bugs difíciles de reproducir.

Al fijar la escala en el compact constructor, el problema desaparece de raíz.

### `RoundingMode.UNNECESSARY`

Si alguien construye `Money.of("34.567", "EUR")`, salta una `ArithmeticException`. **Es
intencionado.** Redondear dinero en silencio es cómo se pierden céntimos a escala de millones de
transacciones. Preferimos fallar en el borde del sistema.

### Operaciones que fallan rápido

`plus`, `minus` y `compareTo` rechazan monedas distintas. No existe un orden bien definido entre 10 €
y 10 $ sin un tipo de cambio, así que lanzamos en vez de inventar una respuesta.

Sí permitimos resultados negativos: un `Money` negativo es legítimo (un reembolso, un saldo
deudor). Es la **regla de negocio** la que decide si un precio puede ser negativo, no el tipo.

## 4. Identificadores tipados

```java
public record ListingId(UUID value) { ... }
public record SellerId(UUID value) { ... }
```

Envolver el `UUID` cuesta prácticamente nada en runtime y elimina toda una familia de bugs: el
compilador ya no deja pasar un `sellerId` donde se esperaba un `listingId`. Ambos son `UUID`, pero
ya no son el mismo tipo.

## 5. `sealed interface`: el corazón del modelo

```java
public sealed interface Listing permits ProductListing, ServiceListing {
    ListingId id();
    SellerId sellerId();
    String title();
    Money price();
    ListingStatus status();
    Listing withStatus(ListingStatus newStatus);
    int availableUnits();

    default boolean acceptsOrders() { return status().acceptsOrders(); }
}
```

`sealed` declara una **lista cerrada** de implementaciones. Es la traducción a Java de un
`sealed trait` de Scala o una `sealed class` de Kotlin.

Lo que compra:

```java
int available = switch (listing) {
    case ProductListing product -> product.availableStock();
    case ServiceListing service -> service.maxConcurrentBookings();
};   // ← sin rama default, y compila
```

El compilador sabe que esos dos casos agotan las posibilidades. **El día que añadamos un tercer tipo
de listing, este switch deja de compilar** — y con él, todos los sitios del sistema que hay que
revisar. Ese fallo de compilación es una funcionalidad, no un estorbo: es lo contrario a una rama
`default` que se traga el caso nuevo en silencio y lo descubres en producción.

### Retorno covariante en `withStatus`

La interfaz declara `Listing withStatus(...)`, pero `ProductListing` lo estrecha a
`ProductListing withStatus(...)`. Java lo permite (tipos de retorno covariantes), y el llamante no
pierde información de tipo al cambiar de estado — no hace falta ningún cast.

## 6. `ListingStatus`: un enum con comportamiento

```java
public enum ListingStatus {
    DRAFT, PUBLISHED, PAUSED, ARCHIVED;

    public boolean isVisibleToBuyers() { return this == PUBLISHED || this == PAUSED; }
    public boolean acceptsOrders()     { return this == PUBLISHED; }
    public boolean isTerminal()        { return this == ARCHIVED; }
}
```

La regla vive junto al dato. La alternativa —un enum anémico y `if (status == PUBLISHED)` repartido
por veinte ficheros— garantiza que tarde o temprano dos sitios discrepen sobre qué significa
"comprable".

## 7. `FulfillmentCheck`: un ADT en lugar de un booleano

```java
public sealed interface FulfillmentCheck {
    record Fulfillable(Money total) implements FulfillmentCheck {}
    record NotAcceptingOrders(ListingStatus status) implements FulfillmentCheck {}
    record InsufficientAvailability(int requested, int available) implements FulfillmentCheck {}
}
```

Tres formas de responder "¿puedo comprar 5 unidades de esto?":

| Opción | Problema |
|---|---|
| `boolean` | Pierde el porqué. La capa de arriba tiene que adivinar el motivo del rechazo. |
| Lanzar una excepción | Usa control de flujo excepcional para un caso perfectamente normal. Caro y ruidoso. |
| **ADT** | El resultado *es* el motivo, con sus datos. El consumidor hace un switch exhaustivo. |

Los records anidados dentro de la interfaz se declaran implícitamente `static` y `final`, y como
están en el mismo fichero, no hace falta ni `permits`: el compilador deduce la lista.

### Record patterns

```java
return switch (result) {
    case Fulfillable(var total) ->
            "Disponible por un total de " + total;
    case NotAcceptingOrders(var status) ->
            "La publicación no admite pedidos (estado: " + status + ")";
    case InsufficientAvailability(int requested, int available) ->
            "Solo quedan %d unidades y se pidieron %d".formatted(available, requested);
};
```

`case Fulfillable(var total)` es un **record pattern**: comprueba el tipo *y* desestructura sus
componentes en variables, en un solo paso. Es el equivalente al pattern matching de Scala, ya
estándar en Java desde la 21.

## 8. Testing

```
src/test/java/com/marketplace/
├── shared/domain/MoneyTest.java
└── catalog/domain/ListingsTest.java
```

**Ninguno lleva `@QuarkusTest`.** Es JUnit 5 puro: sin contenedor CDI, sin arrancar la aplicación,
sin Docker. Corren en milisegundos.

Esto es una consecuencia directa de haber mantenido el dominio libre de framework, y es un objetivo
de diseño explícito: la mayoría de tus tests deberían poder ser así. Los `@QuarkusTest`, que sí
arrancan la aplicación, se reservan para lo que de verdad necesita el framework (endpoints,
persistencia, mensajería) — y llegan en el módulo 2.

```bash
./gradlew test
```

Los tests usan `@Nested` para agrupar por comportamiento y `@DisplayName` para que el informe se lea
como una especificación del negocio, no como una lista de nombres de método.

---

## Preguntas de repaso

1. ¿Por qué normalizamos la escala del `BigDecimal` dentro del compact constructor y no en un
   método `normalize()` que llame el usuario?
2. Un `switch` exhaustivo sobre un tipo `sealed` no admite `default`. ¿Qué pasa si lo añades igual?
   ¿Qué garantía pierdes?
3. `FulfillmentCheck` es un ADT. ¿Cuándo **no** merece la pena y sí basta un `boolean`?
4. ¿Por qué `Money` permite importes negativos si un precio nunca lo es?

## Estado del proyecto al terminar el módulo

- 8 clases de dominio, cero dependencias de framework
- 25 tests en verde, sin Docker ni contenedor CDI
- Siguiente: exponerlo por REST con Quarkus REST + ARC + Bean Validation
