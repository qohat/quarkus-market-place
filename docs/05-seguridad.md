# Módulo 5 — Seguridad: OIDC, JWT y autorización a nivel de recurso

El módulo que cierra un agujero que llevaba abierto desde el módulo 2, y que introduce el fallo
de seguridad más común en APIs REST.

---

# 1 · El agujero que veníamos arrastrando

Desde el módulo 2, `CreateProductRequest` **no** tenía campo `sellerId`. El razonamiento era
correcto:

> *«Si el cliente pudiera elegirlo, cualquiera publicaría en nombre de otro.»*

Pero entonces el vendedor llegaba por la cabecera `X-Seller-Id`, que es exactamente el mismo
problema con otro nombre. Y había uno peor:

```
POST /listings/{id}/archive
```

Sin autenticación y sin comprobar de quién era la publicación: cualquiera con `curl` podía
archivar el catálogo entero.

Este módulo cierra las dos cosas. La segunda es bastante más difícil que la primera.

---

# 2 · Las siglas, en orden histórico

Cada una nació para arreglar una carencia de la anterior.

## OAuth 2.0 — autorización delegada

Resuelve **dar acceso a un tercero sin darle tu contraseña**. Le das un token que sirve solo para
lo que sea, caduca y se puede revocar.

**OAuth 2.0 no sabe quién eres.** Está diseñado para permisos, no para identidad. Un token OAuth
dice «el portador puede leer ventas», no «el portador es Qohat». Durante años se usó igualmente
para login, cada uno a su manera, y salió mal.

## OIDC — identidad, encima de OAuth

Una capa fina sobre OAuth 2.0 que estandariza el «quién eres»:

| Aporte | Para qué |
|---|---|
| **ID token** | Un JWT que afirma la identidad del usuario |
| **`/userinfo`** | Endpoint estándar para pedir datos del usuario |
| **Descubrimiento** | `/.well-known/openid-configuration`, desde donde se autoconfigura todo |

**OIDC = OAuth 2.0 + identidad estandarizada.** Nada más, y es suficiente.

## JWT — el formato del token

```
eyJhbGciOiJSUzI1NiIsImtpZCI6ImFiYyJ9 . eyJzdWIiOiJxb2hhdCIsImV4cCI6MTc... . MEUCIQDf...
└──────────── header ──────────────┘   └──────────── payload ─────────┘   └─ firma ─┘
```

Un token real de nuestro Keycloak, decodificado:

```json
{
  "exp": 1786226629,        ← caduca en 600 segundos
  "iat": 1786226029,        ← emitido ahora
  "jti": "onrtro:5079...",  ← id único del token
  "iss": "http://localhost:49735/realms/quarkus",
  "sub": "0b1d1439-167f-43b3-9fda-717f82cd7581",    ← EL VENDEDOR
  "typ": "Bearer",
  "azp": "quarkus-app",     ← qué aplicación lo pidió
  "scope": "microprofile-jwt",
  "upn": "vendedora",       ← nombre de usuario
  "groups": ["seller"]      ← LOS ROLES
}
```

Cabecera: `{ "alg": "RS256", "typ": "JWT", "kid": "BW7TlETnuj8qsTkUlmDjOF893rReAIAZelB4WRxaNxw" }`

### Cuatro cosas que enseña este token

**`RS256` + `kid`.** Firma asimétrica; el `kid` indica cuál de las claves publicadas en el JWKS
verifica este token. Es lo que permite al emisor **rotar claves** sin romper a nadie.

**Caduca en 10 minutos.** No es tacañería: es la única defensa contra un token robado, porque
**un JWT no se puede revocar**. Validarlo sin preguntar a nadie es su virtud y su límite; si echas
a alguien, su token vive hasta que expire. De ahí los *refresh tokens*.

**Los roles están en `groups`, no en `realm_access.roles`.** Es consecuencia del scope
`microprofile-jwt`. Un Keycloak de producción suele ponerlos en `realm_access.roles`, y entonces
`@RolesAllowed` **rechaza a todo el mundo** hasta que se añade:

```properties
quarkus.oidc.roles.role-claim-path=realm_access/roles
```

**Se usa `sub`, no `upn` ni el email.** Los nombres de usuario y los correos **cambian**; el `sub`
es inmutable. Si identificas a tus vendedores por email, el día que alguien lo cambie pierde su
catálogo.

## Por qué JWT, de verdad

```
SESIÓN CLÁSICA                          JWT
cookie → id de sesión                   cabecera → token firmado
      ↓                                        ↓
consulta a un almacén compartido        verificación de la FIRMA
      ↓                                  con una clave pública ya descargada
una llamada de red por petición         cero llamadas de red
```

**El servicio valida el token él solo.** En un sistema con 30 microservicios, la alternativa —un
almacén central de sesiones consultado en cada petición— es un cuello de botella y un punto único
de fallo.

---

# 3 · Los tres niveles de control de acceso

Este es el esqueleto del módulo:

```
autenticación             ¿quién eres?                401   ← lo da OIDC
autorización por rol      ¿qué tipo de usuario eres?  403   ← lo da @RolesAllowed
autorización por recurso  ¿es TUYO esto?              403   ← NO te lo da nadie
```

El tercero se llama **autorización horizontal**, y su fallo tiene nombre propio: **BOLA**
(*Broken Object Level Authorization*), el riesgo **número 1** del OWASP API Security Top 10.

```java
@POST @Path("/{id}/archive")
@RolesAllowed("seller")          // ← NO es suficiente
```

Esa anotación comprueba que eres *un* vendedor. No comprueba que seas **el** vendedor de esa
publicación. Con ese código, cualquier vendedor registrado puede archivar el catálogo de sus
competidores. Y no lo detecta ninguna herramienta automática, porque desde fuera la petición es
indistinguible de una legítima.

---

# 4 · Las decisiones de diseño

## El `sub` ES el `sellerId`

```java
public record SellerId(UUID value) { ... }
```

El `sub` de Keycloak ya es un UUID, así que la conversión es directa: `SellerId.of(sub)`. Cero
infraestructura, cero consultas extra, y el dominio no se entera de que Keycloak existe.

El coste es quedar atado al emisor: si se migra de proveedor, todos los `sellerId` cambian. Se
mitigará, el día que haga falta, guardando el `sub` en un campo `external_id` de una tabla
`seller` propia.

## La propiedad se comprueba en el caso de uso

No con un interceptor ni en el recurso REST. Tres razones:

1. **Es una regla de negocio**, no de transporte.
2. **Protege todas las puertas.** Una comprobación en el recurso REST solo protege HTTP; el
   consumidor de Kafka del módulo 7 entraría por detrás.
3. **Se testea sin Quarkus.** Los cuatro tests de propiedad corren en milisegundos.

## La identidad entra como parámetro explícito

```java
public Listing archive(ListingId id, SellerId requester)
```

En lugar de un puerto `CurrentSeller` inyectado. La ventaja decisiva: **la firma declara la
dependencia**, así que es imposible olvidarla — el compilador no deja. Un `CurrentSeller`
inyectado deja la dependencia implícita, y nada avisa de que ese método depende de quién llama.

Lo comprobamos al cambiar la firma: el build se rompió en exactamente los sitios que había que
tocar.

## La comprobación va en un único punto

```java
private Listing transitionTo(ListingId id, ListingStatus newStatus, SellerId requester) {
    var listing = byId(id);                                  // 404 si no existe
    if (!listing.sellerId().equals(requester)) {
        throw new NotTheOwnerException(id, requester);       // 403 si no es tuya
    }
    var updated = listing.withStatus(newStatus);
    repository.save(updated);
    return updated;
}
```

`publish`, `pause` y `archive` pasan todos por aquí. Añadir mañana *destacar* o *renovar* obliga a
pasar por el mismo sitio, así que **nace protegida**. Una comprobación copiada tres veces es una
comprobación que alguien olvidará la cuarta.

**El orden importa:** primero se resuelve la publicación, después la propiedad. Al revés no se
puede —hasta cargarla no se sabe de quién es— y además evita que un atacante distinga los ids que
existen de los que no por el tipo de error.

## 403 o 404: cuándo mentir

| | Qué comunica |
|---|---|
| **403 Forbidden** | «Existe, pero no es tuyo» |
| **404 Not Found** | «Aquí no hay nada» — miente a propósito |

El 404 es más hermético: impide **enumerar** qué recursos existen. Aquí se eligió **403** porque
las publicaciones **ya son públicas** vía `GET /listings/{id}`: fingir que no existe no oculta
nada comprobable con otra petición, y a cambio deja al dueño legítimo con un mensaje
incomprensible cuando se equivoca de cuenta.

La regla general es la contraria: cuando el recurso **también** es privado para lectura —los
pedidos del módulo 6— responder 404 y no confirmar ni su existencia.

## Denegar por defecto

```properties
quarkus.security.deny-unannotated-endpoints=true
```

Sin esto, un endpoint sin anotación queda **abierto**, y el día que alguien añada uno y olvide
anotarlo habrá un agujero que ninguna prueba detecta: el endpoint funciona, simplemente funciona
para cualquiera.

Con esta línea, olvidarse produce un 403 en el primer intento. **El fallo pasa de silencioso y
peligroso a ruidoso e inofensivo.** El precio es declarar explícitamente lo público con
`@PermitAll`, que es un precio justo: abrir un endpoint pasa a ser una decisión escrita.

---

# 5 · Lo que enseñaron los fallos

## Activar la seguridad puso 21 tests en rojo, todos con el mismo error

```
Expected status code <400> but was <401>.
```

Esos tests mandaban peticiones sin token y esperaban un 400 de validación. **Los 21 fallos eran
la prueba de que la seguridad funcionaba.** Y revelaron el orden de la cadena:

```
401 autenticación → 403 rol → 400 validación → 403 propiedad
```

**La seguridad se evalúa antes que la validación**, y tiene que ser así: validar el cuerpo de un
anónimo es trabajo regalado, y sus mensajes de error serían un mapa gratis de la API para quien no
debería estar ahí. Queda fijado en `authenticationRunsBeforeValidation`.

## `@Nested` volvió a morder, por segunda vez

| Módulo | Anotación perdida | Síntoma |
|---|---|---|
| 3 | `@TestTransaction` | 13 errores de despliegue |
| 5 | `@TestSecurity` | 21 tests con 401 |

**Las clases `@Nested` de JUnit no heredan las extensiones de Quarkus de la clase externa.** Hay
dos salidas: aplanar el fichero (lo que se hizo en el módulo 3) o repetir la anotación en cada
clase anidada (lo que se hizo aquí). Si aparece una tercera vez, conviene asumir que `@Nested` y
Quarkus no se llevan bien y dejar de usarlo.

## `@OidcSecurity`, no `@JwtSecurity`

Hay dos anotaciones parecidas en artefactos distintos:

| Anotación | Artefacto | Para |
|---|---|---|
| `@JwtSecurity` | `quarkus-test-security-jwt` | `smallrye-jwt` |
| **`@OidcSecurity`** | `quarkus-test-security-oidc` | **`quarkus-oidc`** |

## `@QuarkusTestResource(KeycloakTestResourceLifecycleManager.class)` sobra con Dev Services

Levanta un **segundo** Keycloak que choca con el que ya arrancó Dev Services, y además trae sus
usuarios de ejemplo en vez de los del proyecto. Síntoma: `TestAbortedException: Boot failed`.
`KeycloakTestClient` se autoconfigura solo leyendo las propiedades que publica Dev Services.

---

# 6 · La estrategia de tests

Tres capas, cada una comprobando lo que las otras no pueden:

| Test | Cómo autentica | Qué prueba | Coste |
|---|---|---|---|
| `ListingCatalogTest` | nada, es dominio puro | **las reglas de propiedad** | milisegundos |
| `ListingSecurityTest` | `@TestSecurity` | los códigos 401/403/404 y qué se filtra | rápido |
| `RealTokenSecurityIT` | token real de Keycloak | **el cableado**: firma, emisor, claim de roles | segundos |

La tercera capa es la que casi nadie escribe y la que más falta hace. `@TestSecurity` construye la
`SecurityIdentity` directamente y **se salta toda la validación**, así que con solo esa capa estos
fallos pasarían inadvertidos:

- roles en un claim que Quarkus no lee (`groups` frente a `realm_access.roles`)
- una firma que no se verifica de verdad
- un emisor o una URL de JWKS mal configurados

Con la suite entera en verde, la aplicación rechazaría a todos sus usuarios en producción.

El test que mejor resume el módulo:

```java
@TestSecurity(user = "rival", roles = "seller")
void roleAloneIsNotEnough() {
    given().post("/listings/{id}/publish", propia).then().statusCode(200);
    given().post("/listings/{id}/publish", ajena ).then().statusCode(403);
}
```

Mismo token, mismo rol, mismo endpoint. **Lo único que cambia es de quién es el recurso.**

Y el epitafio de la cabecera vieja, en `ListingValidationTest`: mandar
`X-Seller-Id: 99999999-...` ahora se **ignora** por completo. Si alguien la resucitara, ese test
se pondría rojo.

---

# 7 · Cómo probarlo a mano

```bash
sdk env && ./gradlew quarkusDev     # levanta Keycloak y PostgreSQL solos

# el puerto de Keycloak es aleatorio; se descubre así:
KC=$(docker ps --filter ancestor=quay.io/keycloak/keycloak:26.7.0 \
      --format "{{.Ports}}" | grep -o '0.0.0.0:[0-9]*' | cut -d: -f2 | head -1)

# lo que descubre un cliente antes de nada
curl -s "http://localhost:$KC/realms/quarkus/.well-known/openid-configuration" | jq

# pedir un token como haría una aplicación real
TOKEN=$(curl -s -X POST \
  "http://localhost:$KC/realms/quarkus/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=quarkus-app -d client_secret=secret \
  -d username=vendedora -d password=vendedora | jq -r .access_token)

# mirarlo por dentro: el payload NO está cifrado, solo codificado
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq

# sin token → 401
curl -i -X POST localhost:8080/listings/products \
  -H 'Content-Type: application/json' \
  -d '{"title":"Teclado","amount":"25.00","currency":"EUR","stock":1}'

# con token → 201, y el sellerId sale del sub
curl -i -X POST localhost:8080/listings/products \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"Teclado","amount":"25.00","currency":"EUR","stock":1}'

# con el token del comprador → 403 (autenticado, pero sin el rol)
```

Usuarios de Dev Services: `vendedora`/`vendedora` (seller), `comprador`/`comprador` (buyer),
`rival`/`rival` (seller). La contraseña es igual al nombre.

## Para probar por tu cuenta

| Prueba | Qué esperar |
|---|---|
| Crear con `vendedora` y archivar con el token de `rival` | 403 con `type: not-the-owner` |
| Cambiar un carácter de la firma del token | 401 |
| Esperar 10 minutos y reutilizar el token | 401 por `exp` |
| Quitar `@PermitAll` de `GET /listings` | El catálogo público deja de serlo |
| Quitar `@RolesAllowed` de un POST | 403 gracias a `deny-unannotated-endpoints` |

## Los mandamientos

1. **Un JWT no es un secreto.** El payload es legible por cualquiera; es un documento firmado.
2. **Identifica por `sub`**, nunca por email ni nombre de usuario.
3. **El rol no es la propiedad.** `@RolesAllowed` no protege de los competidores.
4. **La propiedad se comprueba en el dominio**, para que valga en todas las puertas.
5. **Denegar por defecto.** Que olvidarse duela pronto y poco.
6. **Comprueba la existencia antes que la propiedad**, o filtrarás qué ids existen.
7. **Al cliente lo justo, al log todo.**
8. **Ten al menos un test con token real**, o no sabrás si la validación está siquiera enchufada.
