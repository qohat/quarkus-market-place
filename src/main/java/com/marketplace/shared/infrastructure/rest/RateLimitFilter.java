package com.marketplace.shared.infrastructure.rest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Control de admisión: rechaza rápido cuando un cliente pide demasiado.
 *
 * <h2>Por qué esto cierra el módulo 4</h2>
 *
 * Allí medimos que el modelo bloqueante saturado <strong>no daba errores</strong>: seguía
 * aceptando peticiones y las encolaba hasta responder en un segundo lo que costaba cien
 * milisegundos. Y vimos que los virtual threads no arreglan eso — lo empeoran, porque aceptan
 * todavía más trabajo del que pueden hacer:
 *
 * <blockquote>
 * Los virtual threads no eliminan la saturación, la mueven de sitio. Con 200 hilos, la petición
 * 201 se rechaza rápido. Con hilos virtuales, la 20.001 se acepta y hace cola.
 * </blockquote>
 *
 * La conclusión de aquel módulo era que <strong>saturar sin errores es peor que fallar</strong>, y
 * que la respuesta es rechazar pronto. Esto es rechazar pronto.
 *
 * <h2>Cubo de fichas (token bucket)</h2>
 *
 * Cada cliente tiene un cubo con {@code capacity} fichas que se repone a {@code refillPerSecond}.
 * Cada petición gasta una; sin fichas, 429.
 *
 * <p>La virtud frente a un contador por ventana fija es que <strong>tolera ráfagas</strong>: un
 * cliente que ha estado callado acumula fichas y puede gastarlas de golpe, que es como se comporta
 * un cliente legítimo al abrir una pantalla. Un contador por minuto rechazaría esa ráfaga y a la
 * vez dejaría pasar el doble de tráfico justo en el cambio de ventana.
 *
 * <h2>Sus dos límites, dichos claramente</h2>
 *
 * <p><strong>Es por instancia.</strong> Con tres réplicas, el límite real es el triple. Para un
 * límite global hace falta estado compartido —Redis— y entonces cada petición cuesta una llamada
 * de red, o se acepta una aproximación. En la práctica, casi todo el mundo pone esto en el
 * balanceador o en la pasarela de API, donde el límite sí es global y no consume recursos de la
 * aplicación. Aquí vive en el código porque es donde se puede leer y probar.
 *
 * <p><strong>Y el mapa crece.</strong> Una entrada por cliente, sin caducidad, es una fuga de
 * memoria lenta y un vector de ataque: basta con rotar identificadores. En producción esto sería
 * una caché con expiración, no un {@code ConcurrentHashMap}.
 */
@Provider
@ApplicationScoped
public class RateLimitFilter implements ContainerRequestFilter {

    /** Rutas que nunca se limitan: sin ellas, Kubernetes no puede sondear un servicio saturado. */
    private static final String[] EXENTAS = {"/q/health", "/q/metrics", "/q/openapi"};

    @ConfigProperty(name = "marketplace.rate-limit.capacity", defaultValue = "100")
    long capacity;

    @ConfigProperty(name = "marketplace.rate-limit.refill-per-second", defaultValue = "50")
    long refillPerSecond;

    @ConfigProperty(name = "marketplace.rate-limit.enabled", defaultValue = "true")
    boolean enabled;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Counter rechazadas;

    RateLimitFilter(MeterRegistry registry) {
        this.rechazadas = Counter.builder("marketplace.rate_limit.rejected").register(registry);
    }

    @Override
    public void filter(ContainerRequestContext context) {
        if (!enabled) {
            return;
        }
        var path = context.getUriInfo().getPath();
        for (var exenta : EXENTAS) {
            if (path.startsWith(exenta)) {
                return;
            }
        }

        var cliente = identificar(context);
        if (!buckets.computeIfAbsent(cliente, k -> new Bucket(capacity, refillPerSecond)).tryConsume()) {
            rechazadas.increment();
            context.abortWith(Response.status(429)
                    // Retry-After no es decoración: le dice al cliente CUÁNDO volver. Sin esa
                    // cabecera, un cliente bien programado reintenta a ciegas y uno mal
                    // programado reintenta en bucle, que es justo lo que no quieres de quien ya
                    // estaba pidiendo demasiado.
                    .header("Retry-After", 1)
                    .type(ProblemDetail.MEDIA_TYPE)
                    .entity(ProblemDetail.type("rate-limit-exceeded")
                            .title("Too many requests")
                            .status(Response.Status.fromStatusCode(429))
                            .detail("Rate limit exceeded. Try again in a moment.")
                            .instance(path)
                            .build())
                    .build());
        }
    }

    /**
     * Quién es el cliente.
     *
     * <p>Se prefiere el usuario autenticado a la dirección IP, y no es un detalle menor: detrás de
     * una IP puede haber una empresa entera saliendo por el mismo NAT, y limitarla castigaría a
     * cientos de personas por lo que hace una. Con el usuario, cada cual responde de su propio
     * consumo.
     */
    private String identificar(ContainerRequestContext context) {
        var principal = context.getSecurityContext().getUserPrincipal();
        if (principal != null) {
            return "user:" + principal.getName();
        }
        var forwarded = context.getHeaderString("X-Forwarded-For");
        return "ip:" + (forwarded != null ? forwarded.split(",")[0].trim() : "anonymous");
    }

    /**
     * Cubo de fichas sin bloqueos.
     *
     * <p>Todo el estado cabe en un {@code long} —fichas y último instante de reposición— y se
     * actualiza con {@code compareAndSet}. Un {@code synchronized} aquí sería un punto de
     * contención en el camino de TODAS las peticiones, y además fijaría el hilo virtual a su
     * carrier en Java 23 o anterior (módulo 4).
     */
    private static final class Bucket {
        private final long capacity;
        private final long refillPerSecond;
        private final AtomicLong tokens;
        private final AtomicLong lastRefillNanos;

        Bucket(long capacity, long refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            this.tokens = new AtomicLong(capacity);
            this.lastRefillNanos = new AtomicLong(System.nanoTime());
        }

        boolean tryConsume() {
            refill();
            long actuales;
            do {
                actuales = tokens.get();
                if (actuales <= 0) {
                    return false;
                }
            } while (!tokens.compareAndSet(actuales, actuales - 1));
            return true;
        }

        private void refill() {
            long ahora = System.nanoTime();
            long ultimo = lastRefillNanos.get();
            long transcurridos = ahora - ultimo;
            long nuevas = transcurridos * refillPerSecond / 1_000_000_000L;
            if (nuevas > 0 && lastRefillNanos.compareAndSet(ultimo, ahora)) {
                tokens.updateAndGet(t -> Math.min(capacity, t + nuevas));
            }
        }
    }
}
