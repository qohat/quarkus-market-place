# Módulo 3 — Persistencia

> Siglas: **JPA**, **ORM**, **JDBC**, **Dev Services**, **Flyway**, **N+1**.
> Ver [GLOSARIO.md](GLOSARIO.md) y [PREGUNTAS-RESPUESTAS.md](PREGUNTAS-RESPUESTAS.md).

## 1. Dev Services: PostgreSQL sin configurar nada

```properties
quarkus.datasource.db-kind=postgresql
```

Eso es **toda** la configuración de base de datos para desarrollo y test. Sin URL, sin usuario,
sin contraseña, sin puerto.

Al ver un driver JDBC declarado y ninguna URL configurada, Quarkus arranca un contenedor
PostgreSQL con Testcontainers, lo cablea y lo apaga al salir. En producción se dan las variables
de entorno (`QUARKUS_DATASOURCE_JDBC_URL`…) y Dev Services **se desactiva solo**.

La ventaja de fondo no es la comodidad, es la **fidelidad**: los tests corren contra el mismo
motor que producción. Usar H2 en test y PostgreSQL en producción es una fuente clásica de bugs
que solo aparecen al desplegar.

También arranca `testcontainers/ryuk`, un contenedor vigilante que mata a los demás si tu proceso
muere de golpe, para que un `kill -9` no deje contenedores huérfanos.

## 2. Flyway manda sobre las entidades

Por defecto Quarkus pone la estrategia en `drop-and-create` y deja que Hibernate genere el
esquema. Cómodo al empezar, trampa a medio plazo: el esquema real acaba siendo lo que Hibernate
improvise, sin control de versiones ni forma de reproducirlo.

```properties
quarkus.hibernate-orm.schema-management.strategy=validate
quarkus.flyway.migrate-at-start=true
```

Con `validate`, Hibernate **no toca** el esquema; solo comprueba al arrancar que las entidades
encajan con las tablas que creó Flyway. Añadir un campo sin migración deja de compilar el
arranque.

> Esa red de seguridad se cobró su primera pieza de inmediato:
> ```
> wrong column type in price_currency: found [bpchar], but expecting [char(3)]
> ```
> `CHAR` en PostgreSQL es `bpchar` (*blank-padded*): rellena con espacios hasta la longitud fija.
> Para un código de moneda es doblemente malo. **`CHAR` casi nunca es la opción correcta en
> PostgreSQL**: no ahorra un byte y añade sorpresas.

### Decisiones del esquema que conviene poder defender

| Decisión | Por qué |
|---|---|
| `Money` → dos columnas (importe + moneda) | Un número sin moneda no es dinero. Con una sola columna, la segunda divisa suma peras con manzanas sin que nada falle. |
| `NUMERIC(19,4)`, no `DOUBLE PRECISION` | `NUMERIC` es decimal exacto; `DOUBLE` es binario IEEE-754 y no representa `0.10` con exactitud. Escala 4 deja sitio a monedas de tres decimales. |
| `TIMESTAMPTZ`, no `TIMESTAMP` | Guarda el instante absoluto, sin depender de la zona del servidor —distinta en tu portátil y en el clúster. |
| `VARCHAR`, no `CHAR` | Ver arriba. |
| CHECK constraints que repiten invariantes del dominio | La aplicación no es el único camino hacia los datos: migraciones, backfills y sesiones manuales de `psql` escriben directamente. |
| Índice **parcial** sobre las visibles | En un marketplace maduro casi todo acaba archivado; indexar esas filas desperdicia espacio en una consulta que no las mira. |

## 3. Entidades separadas del dominio

JPA exige constructor sin argumentos, campos mutables y clases no `final` (para sus proxies).
Todo eso es incompatible con records inmutables, compact constructors que garantizan invariantes
y sealed interfaces.

Anotar el dominio con JPA obligaría a renunciar a lo que lo hace bueno. El precio de mantenerlos
separados es una traducción explícita; el retorno es que **el dominio nunca ve una anotación de
Hibernate**.

```
ListingEntity (abstracta, @Inheritance SINGLE_TABLE)
├── ProductListingEntity  @DiscriminatorValue("PRODUCT")
└── ServiceListingEntity  @DiscriminatorValue("SERVICE")
```

Dos trampas evitadas:

- **`@Enumerated(EnumType.STRING)`, nunca `ORDINAL`.** `ORDINAL` guarda la *posición* del valor en
  el enum. Insertar o reordenar una constante cambia el significado de todas las filas
  existentes, en silencio.
- **`Integer` y no `int`** en las columnas de subtipo. Son `NULL` para el otro subtipo, y un
  primitivo leería ese `NULL` como `0`, volviendo indistinguible un producto agotado de un
  servicio.

## 4. Panache

```java
@ApplicationScoped
public class PanacheListingRepository
        implements ListingRepository,                          // nuestro puerto
                   PanacheRepositoryBase<ListingEntity, UUID>  // la ayuda de Quarkus
```

`PanacheRepositoryBase` no tiene métodos que implementar: son `default` vacíos, y **Quarkus genera
el cuerpo real dentro de tu clase** durante la augmentation. Misma técnica del módulo 0.

Dos sutilezas que aparecieron al montarlo:

1. **Java no elige entre un `abstract` de una interfaz y un `default` de otra sin relación.** Hay
   que declarar el método explícitamente. Sin eso, no compila.
2. **Declararlo te saca de la generación.** El primer intento, delegar en
   `PanacheRepositoryBase.super.count()`, falla en runtime con *"normally automatically overridden
   in subclasses"*: la generación se saltó ese método y el `super` alcanza el stub vacío. La salida
   es usar el `EntityManager` — buen recordatorio de que Panache es azúcar sobre JPA, no un
   sustituto.

### Dirty checking

```java
findByIdOptional(id).ifPresentOrElse(
        existing -> existing.updateFrom(listing),   // no se llama a ningún save
        () -> persist(ListingEntity.fromDomain(listing)));
```

Dentro de una transacción, **modificar una entidad gestionada ya la persiste**: Hibernate compara
su estado con el que leyó y emite el UPDATE al cerrar. Es potente y es una fuente clásica de
escrituras accidentales.

## 5. Transacciones: en el caso de uso, no en el repositorio

`@Transactional` va sobre `ListingCatalog`. Una transacción delimita una **unidad de trabajo del
negocio**, y quien sabe dónde empieza y acaba es esa capa.

Por método de repositorio, un `publish()` que lee, decide y escribe abriría *tres* transacciones
independientes, y un fallo a medias dejaría commiteado lo anterior.

> Será crítico en el módulo 7: el patrón **outbox** depende de que el estado y el evento se
> escriban en la misma transacción.

## 6. Paginación

Los métodos sin paginar **se eliminaron**, no conviven. Un `findAll()` sin límite funciona durante
meses y revienta el día que hay cien mil filas; si el método no existe, nadie lo llama por
descuido.

### El límite de tamaño es control de admisión

Sin él, `?size=1000000` es una denegación de servicio de un carácter. `PageRequest` lo garantiza
para cualquier vía de entrada, no solo HTTP.

### El orden necesita desempate

```sql
ORDER BY title, id
```

Con `ORDER BY title` a secas, las filas que comparten título salen en un orden que PostgreSQL **no
garantiza** y puede variar entre consultas. Al pedir páginas sucesivas, un elemento reaparece y
otro se pierde.

Es un bug que **ningún test de una sola página encuentra**. El que lo caza recorre todas las
páginas y comprueba que cada id aparece exactamente una vez.

### Offset vs keyset

`OFFSET` degrada linealmente: servir la página 5.000 obliga a PostgreSQL a recorrer y descartar
100.000 filas. La **paginación por keyset** ("dame los 20 siguientes a este valor") cuesta lo mismo
a cualquier profundidad, a cambio de perder el salto a una página arbitraria. Por eso la usan los
feeds con scroll infinito y no las tablas con numeritos.

## 7. Bloqueo optimista

El problema es el **lost update**:

```
T1 lee stock=10 · T2 lee stock=10 · T1 escribe 9 · T2 escribe 9
→ se vendieron dos unidades, el stock bajó una. Nadie falla. Nadie se entera.
```

`@Version` añade un contador que Hibernate mete en el `WHERE`:

```sql
UPDATE listing SET ..., version = 3 WHERE id = ? AND version = 2
```

Si otro escribió primero, el UPDATE afecta a cero filas → `OptimisticLockException` → **409
Conflict** (no 500: no ha fallado nada, solo llegó tarde).

| | Optimista | Pesimista (`SELECT … FOR UPDATE`) |
|---|---|---|
| Bloquea | no | sí |
| Escala | bien | serializa el acceso |
| Deadlocks | imposibles | posibles |
| Coste del conflicto | se repite el trabajo | se espera |
| Cuándo | conflictos raros (editar una ficha) | conflictos habituales (descontar stock en un flash sale) |

> **Limitación honesta del estado actual:** la versión no llega al dominio ni al cliente, así que
> esto protege dentro de una sesión de Hibernate pero **no** el lost update entre dos peticiones
> HTTP —nuestro `save()` recarga la entidad y con ella la versión fresca. Cerrarlo requiere que la
> versión viaje al cliente y vuelva (`ETag` / `If-Match`, o un campo en el DTO). Se aborda en el
> módulo 6.

## 8. El problema N+1 y el presupuesto de consultas

Cargar N elementos y lanzar una consulta extra por cada uno. Con 20 filas en desarrollo pasa
desapercibido; con 5.000 en producción el endpoint tarda treinta segundos.

**Lo insidioso es que no falla.** No hay excepción, no hay log, los tests funcionales pasan. Solo
hay lentitud, y aparece con datos reales.

La defensa es un **test de presupuesto de consultas**, usando las estadísticas de Hibernate:

```java
long consultas = consultasDe(() -> repository.findVisible(PageRequest.of(0, 50)));
assertEquals(2, consultas);   // count + select, sea cual sea el número de filas
```

Convierte un problema de rendimiento invisible en un build rojo.

### El hallazgo que hizo fallar el primer intento

El N+1 "de manual" —listar y luego `findById` de cada elemento— **no produjo N+1**: la caché de
primer nivel ya tenía esas entidades y las sirvió sin tocar la base de datos.

Eso explica por qué el N+1 es tan escurridizo: **el mismo bucle puede ser gratis o catastrófico
según qué haya cargado antes la sesión**. Un cambio inocuo aguas arriba —vaciar la sesión,
reordenar dos llamadas, partir un método en dos transacciones— lo enciende sin tocar el bucle.

El N+1 real se produjo consultando por elemento algo que la caché no puede servir. La solución no
es tocar el bucle, sino resolverlo de una vez con una consulta agregada.

*(Y la caché de primer nivel es un arma de doble filo: en un proceso por lotes largo crece sin
parar y se vuelve una fuga de memoria. De ahí los `entityManager.clear()` periódicos.)*

## 9. Aislamiento de tests

| Tipo de test | Estrategia | Por qué |
|---|---|---|
| Repositorio | `@TestTransaction` | Test y código comparten hilo y transacción: el rollback los cubre |
| REST (RestAssured) | limpieza explícita | La petición HTTP la atiende **otro hilo** con su propia transacción; el rollback del test no la alcanza |
| Dominio | nada | No tocan la base de datos |

> **Trampa encontrada:** `@TestTransaction` es un *interceptor binding*, y **CDI ignora los
> interceptores en clases internas**. Un `@QuarkusTest` que lo use no puede agrupar casos con
> `@Nested` de JUnit. Los tests REST sí lo usan porque solo manejan RestAssured.

---

## Preguntas de repaso

Respondidas en [PREGUNTAS-RESPUESTAS.md](PREGUNTAS-RESPUESTAS.md), sección Módulo 3:

- 3.1 · Si tu suite creciera a 400 tests de integración y 15 minutos, ¿qué harías?
- 3.2 · ¿Qué bug concreto se te escaparía usando H2 en tests y PostgreSQL en producción?
- 3.3 · Los CHECK repiten invariantes del dominio: ¿no contradice la regla de no duplicar reglas?
- 3.4 · `EnumType.ORDINAL`: describe el bug exacto
- 3.5 · `save()` actualiza sin llamar a nada: ¿cómo se llama y qué peligro tiene?
- 3.6 · ¿Por qué `@TestTransaction` funciona en unos tests y no en otros?
- 3.7 · Optimista vs pesimista: ¿cuál usarías para descontar stock en un flash sale?
- 3.8 · ¿Por qué un N+1 no lo detecta ningún test funcional?

## Estado del proyecto

- API completa sobre PostgreSQL real, con esquema versionado
- **106 tests**: dominio puro, repositorio con rollback, REST por HTTP, paginación,
  bloqueo optimista y presupuesto de consultas
- El dominio sigue sin una sola anotación de Hibernate
- Siguiente: **módulo 4**, el eje de concurrencia — bloqueante, reactivo y virtual threads, con
  números medidos
