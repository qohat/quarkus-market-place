# Módulo 8 — Resiliencia y observabilidad

El módulo llega cuando el sistema ya tiene los tres ingredientes que lo hacen necesario: una
pasarela externa que falla, compensaciones que también pueden fallar, y una petición que atraviesa
HTTP → PostgreSQL → Kafka → otro consumidor.

Y recoge lo que quedó apuntado en el 7: *«distinguir un rechazo de un error de red es la decisión
que hace correcta o incorrecta una política de reintentos»*.

---

# 1 · La distinción que lo gobierna todo

Antes de cualquier patrón, una pregunta por cada fallo: **¿tiene sentido volver a intentarlo?**

| | Ejemplo | ¿Reintentar? |
|---|---|---|
| **Transitorio** | Timeout de red, 503, conexión rechazada | **Sí** — la próxima vez puede funcionar |
| **Permanente** | Pago rechazado, 400, validación fallida | **No** — fallará igual las veces que quieras |

Reintentar un fallo permanente no es solo inútil: **gasta cuota en la pasarela y puede disparar sus
controles antifraude**. Es de los errores que se pagan en dinero.

En el código, esa distinción son dos excepciones y dos atributos:

```java
@Retry(retryOn = PaymentGatewayUnavailableException.class,   // técnico → reintentar
       abortOn = PaymentDeclinedException.class)             // negocio → jamás
```

Y está demostrada, no supuesta:

```
fallo técnico  →  4 intentos llegan a la pasarela   (1 inicial + 3 reintentos)
rechazo        →  1 intento. Ni uno más
```

---

# 2 · Los cuatro patrones, y qué protege cada uno

## Timeout — el más importante y el que más se olvida

> **Un servicio lento es peor que uno caído.** Uno caído te falla rápido y sigues con tu vida; uno
> lento retiene tus hilos y tus conexiones hasta arrastrarte con él.

Sin timeout no hay resiliencia posible, porque **ningún otro patrón llega a activarse**: si la
llamada nunca vuelve, no hay fallo que contar ni reintento que hacer.

Medido: con una pasarela que tarda 3 s y un timeout de 2 s, la llamada corta antes. Sin él, cada
compra retendría hilo y conexión esos 3 s — y con 20 conexiones bastan 20 compras simultáneas para
que la aplicación entera deje de responder por culpa de un dependiente que **ni siquiera está
caído, solo lento**.

## Retry — con jitter, que es lo que casi nadie pone

```java
@Retry(maxRetries = 3, delay = 200, jitter = 100, ...)
```

```
sin jitter:  todos reintentan a los 200 ms, luego a los 400, luego a los 800
             ↑ tres picos sincronizados justo cuando el servicio intentaba recuperarse
con jitter:  cada cliente espera un tiempo aleatorio alrededor de esa cifra
```

Sin jitter creas un **rebaño atronador** que remata al servicio que se estaba levantando.

Y un detalle que se confunde constantemente: **`maxRetries = 3` significa 4 llegadas** — un intento
inicial más tres reintentos.

## Circuit breaker — no protege al caído, te protege a ti

```
CLOSED ──(N fallos)──→ OPEN ──(pasa el tiempo)──→ HALF_OPEN ──(éxito)──→ CLOSED
                        │                             │
                   falla al instante            deja pasar unas de prueba
                   sin llamar a nadie
```

Medido: con el circuito abierto, la llamada falla **en menos de 100 ms** y **la pasarela no recibe
ni un intento más**. En vez de esperar 2 s de timeout por tres reintentos en cada compra, respondes
al instante y liberas los recursos para las peticiones que sí se pueden atender.

**Y `skipOn` es imprescindible:**

```java
@CircuitBreaker(..., skipOn = PaymentDeclinedException.class)
```

Sin él, una racha de compradores sin fondos abriría el circuito e **impediría cobrar a quien sí
tiene dinero**. El circuito debe contar fallos de *infraestructura*, jamás resultados de negocio.

## Bulkhead — control de admisión

Limita cuántas llamadas concurrentes haces a un dependiente. Es exactamente lo que echamos de menos
en el módulo 4, cuando vimos que los virtual threads convierten un fallo rápido en una cola
infinita.

---

# 3 · El orden de los interceptores, que no es negociable

```
Fallback  >  Retry  >  CircuitBreaker  >  Timeout  >  Bulkhead
```

**Que Retry envuelva a CircuitBreaker** es lo que impide que los dos patrones se peleen: cada
reintento pasa por el circuito, así que en cuanto este abre, los reintentos dejan de llegar. Con el
orden contrario, el retry seguiría machacando a un servicio ya declarado caído — **el reintento es
el acelerante del incendio**: con 3 reintentos, 1 000 clientes hacen 3 000 llamadas a algo que se
está cayendo.

**Que Timeout esté por dentro de Retry** significa que el límite es **por intento, no total**: 3
intentos de 2 segundos pueden tardar 6. Es la confusión más común al configurar esto, y la que hace
que un cliente espere el triple de lo que su autor creía.

## La política depende de la consecuencia, no de la operación

```java
@Timeout(2s)  @Retry(3)  @CircuitBreaker(...)   → charge
@Timeout(5s)  @Retry(5)  (sin circuit breaker)  → refund
```

El reembolso reintenta más y durante más tiempo, y **no lleva circuit breaker**: si un reembolso se
pierde, el comprador se queda sin su dinero, que es mucho peor que una compra que no llega a
hacerse; y abrir el circuito dejaría reembolsos sin emitir.

**Copiar las mismas anotaciones a todos los métodos es el error de bulto de este módulo.**

---

# 4 · El mensaje envenenado

Un consumidor de Kafka tiene un modo de fallo que no existe en HTTP: si un mensaje no se puede
procesar **nunca** y la estrategia es reintentar, ese mensaje **bloquea su partición para siempre**.
Nada de lo que venga detrás se procesa jamás.

Es de los incidentes más desconcertantes: el sistema no da errores llamativos, simplemente **deja de
avanzar** para una parte del tráfico.

| Estrategia | Qué elige |
|---|---|
| `fail` (por defecto) | Parar el consumidor. Máxima seguridad, disponibilidad cero |
| `ignore` | Seguir y perder el mensaje. Silencioso y peligroso |
| **`dead-letter-queue`** | Apartarlo a otro tema y seguir ← la única que no obliga a elegir |

Demostrado: un mensaje corrupto acaba en `marketplace-events-dlq`, y —lo que de verdad importa— **el
siguiente mensaje de la misma partición se sigue procesando**.

## Las dos clases de mensaje que no se pueden procesar

```
«no es para mí»       →  otro tipo de evento en el tema compartido  →  IGNORAR
«es mío y está roto»  →  payload corrupto, datos imposibles         →  DEJAR FALLAR → DLQ
```

Confundirlas cuesta caro en las dos direcciones. Si se ignora todo, un fallo real desaparece en
silencio. Si se deja fallar todo, **la DLQ se llena de mensajes sanos ajenos** y se vuelve ruido que
nadie revisa — que es como mueren las colas de muertos en la práctica.

---

# 5 · Observabilidad

Tres pilares con costes muy distintos:

| | Qué es | Coste | Responde a |
|---|---|---|---|
| **Logs** | Eventos discretos | Alto por volumen | *«¿qué pasó exactamente aquí?»* |
| **Métricas** | Números agregados | Muy bajo | *«¿va bien el sistema?»* |
| **Trazas** | El camino de una petición | Medio (se muestrea) | *«¿dónde se fue el tiempo?»* |

## La cardinalidad, que es lo que separa a quien ha operado esto

```java
Counter.builder("payments").tag("buyerId", buyerId)    // ✗ una serie temporal POR PERSONA
Counter.builder("payments").tag("result", "declined")  // ✓ tres series en total
```

Meter un identificador como etiqueta de una métrica **hace explotar Prometheus**, y no se nota hasta
que la instancia de monitorización se queda sin memoria.

**La regla: las etiquetas son para dimensiones acotadas; los identificadores van en las trazas y los
logs**, que sí están pensados para alta cardinalidad. Hay un test que lo vigila: cinco compradores
distintos no pueden producir más de tres series.

## Separar rechazos de fallos técnicos

```
marketplace_payments_total{result="charged"}
marketplace_payments_total{result="declined"}     ← problema de NEGOCIO
marketplace_payments_total{result="unavailable"}  ← problema TÉCNICO
```

No es cosmético. Un pico de `declined` es una pasarela que endurece sus reglas o un fraude en curso;
un pico de `unavailable` es un problema de infraestructura. Un único contador de «errores» obliga a
ir a los logs para saber cuál de las dos cosas está pasando.

## El trace id en cada línea de log

```properties
quarkus.log.console.format=%d{HH:mm:ss} %-5p traceId=%X{traceId} spanId=%X{spanId} ...
```

Es lo que convierte tres pilares sueltos en observabilidad de verdad: al ver un error en los logs,
ese identificador lleva directamente a la traza completa de esa petición. Sin él, correlacionar es
buscar por marcas de tiempo.

## La traza sobrevive al salto asíncrono

```java
@Incoming("events-in")
@WithSpan("catalog.apply-stock-changed")
public void onEvent(String payload) { ... }
```

El contexto de traza viaja **en las cabeceras del mensaje de Kafka**, así que ese tramo aparece
colgando de la petición HTTP que originó la compra, aunque se ejecute segundos después y en otro
proceso. Sin él, la traza terminaría al publicar y el trabajo del consumidor quedaría huérfano.

Con Dev Services de LGTM puedes abrirla en Grafana y ver el hueco temporal dibujado.

## Liveness y readiness: la confusión que reinicia tu clúster

```
liveness   ¿estoy vivo?    Si falla → REINICIAR el contenedor
readiness  ¿puedo servir?  Si falla → sacarlo del balanceador, sin reiniciar
```

Un outbox atascado va en **readiness**. Si fuera liveness, Kubernetes entraría en un bucle de
reinicios que **empeoraría** el atasco, porque cada arranque cuesta tiempo durante el cual nadie
vacía la cola.

**La regla: liveness solo para lo que un reinicio pueda arreglar** —un interbloqueo, una fuga de
memoria—. Todo lo demás, incluidas las dependencias externas, es readiness. Meter la base de datos
en liveness significa que, cuando se caiga, **Kubernetes reinicie todas tus instancias a la vez** y
convierta una incidencia en una caída total.

Y el health check devuelve **datos**, no solo un estado: cuántos eventos hay pendientes y desde qué
umbral preocuparse. Uno que solo diga «mal» obliga a ir a buscar por qué, a las tres de la mañana.

---

# 6 · Lo que enseñaron los fallos

| Fallo | Lección |
|---|---|
| El test de `@Retry` veía 2 intentos en vez de 4 | **El circuito se abría a mitad y cortaba los reintentos** — que es el comportamiento correcto. El diagnóstico engañaba: parecía que el retry no reintentaba |
| El contador de intentos incluía el montaje | Estabilizar el circuito exige llamadas reales. El `reset()` va **después** del montaje, no antes |
| La ventana rodante del circuit breaker | No basta con esperar a que cierre: hay que **llenarla de éxitos**, o el test siguiente abre el circuito a los dos fallos |
| Un evento ajeno acababa en la DLQ | **Quarkus desactiva `FAIL_ON_UNKNOWN_PROPERTIES`**, así que cualquier JSON deserializa a `StockChanged` con campos nulos. Hay que comprobar la forma explícitamente |
| El test de DLQ recogía el veneno de otro test | La DLQ es un tema compartido que persiste. Marcador único por test |
| `/q/health` daba 404 | `quarkus-smallrye-health` no lo arrastra ninguna otra extensión |
| Observabilidad fallaba solo en suite completa | Los tests de resiliencia dejaban **el circuito abierto**. Estado en memoria que sobrevive al test: **quien ensucia, recoge** |

El primero y el último son los que más enseñan. El primero porque **el test rojo describía mal el
problema** —parecía un fallo del retry y era el circuito haciendo su trabajo—. El último porque es
la lección del módulo 7 (limpiar lo que escribes) aplicada a algo que **no se ve en ninguna parte**:
el estado de un bean.

---

# 7 · Cómo probarlo

```bash
sdk env && ./gradlew quarkusDev
# la Dev UI muestra la URL de Grafana que levantó Dev Services

./gradlew test --tests "*ResiliencePatternsTest*"   # los patrones, actuando
./gradlew test --tests "*DeadLetterQueueTest*"      # el mensaje envenenado
./gradlew test --tests "*ObservabilityTest*"        # métricas y health checks
```

```bash
curl -s localhost:8080/q/metrics | grep marketplace   # métricas de negocio
curl -s localhost:8080/q/health/ready | jq            # readiness con datos
curl -s localhost:8080/q/health/live | jq
```

## Cosas para probar por tu cuenta

| Prueba | Qué esperar |
|---|---|
| Quitar `abortOn` del `@Retry` | Los rechazos se reintentan 4 veces: cuota gastada y antifraude en alerta |
| Quitar `skipOn` del `@CircuitBreaker` | Una racha de rechazos abre el circuito e impide cobrar a quien sí tiene fondos |
| Poner `jitter = 0` y lanzar carga | Los reintentos se sincronizan en picos |
| Cambiar `failure-strategy` a `fail` | Un mensaje envenenado detiene el consumidor por completo |
| Añadir `.tag("buyerId", ...)` a un contador | Ver crecer las series temporales en `/q/metrics` sin límite |
| Mover el health del outbox a `@Liveness` | Entender por qué eso, en Kubernetes, sería un bucle de reinicios |

## Los mandamientos

1. **Timeout primero.** Sin él, ningún otro patrón llega a activarse.
2. **Un servicio lento es peor que uno caído.**
3. **Distingue transitorio de permanente**, o reintentarás lo que jamás va a funcionar.
4. **Retry sin jitter crea el pico que remata al servicio.**
5. **`maxRetries = 3` son 4 llamadas.**
6. **El circuit breaker te protege a ti**, no al servicio caído.
7. **Que el circuito no cuente fallos de negocio**, o dejarás de cobrar a quien puede pagar.
8. **La política depende de la consecuencia del fallo**, no de la operación.
9. **Cardinalidad acotada en las etiquetas.** Los identificadores, a las trazas.
10. **Liveness solo para lo que un reinicio arregle.**
11. **Un health check devuelve datos, no solo un estado.**
