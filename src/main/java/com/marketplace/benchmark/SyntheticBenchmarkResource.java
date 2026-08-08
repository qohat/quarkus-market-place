package com.marketplace.benchmark;

import io.quarkus.arc.profile.IfBuildProfile;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;

/**
 * Laboratorio sintético: el mismo trabajo resuelto con los tres modelos de concurrencia.
 *
 * <p>El "trabajo" es esperar {@link #IO_LATENCY} sin consumir CPU, que es lo que hace una
 * petición real la mayor parte de su vida: aguardar a la base de datos o a otro servicio.
 * Al no tocar la base de datos, aquí no hay pool de conexiones que limite nada, así que lo
 * único que se mide es el modelo de concurrencia. Esa es justamente la comparación que el
 * escenario con base de datos real vendrá luego a desmentir.
 *
 * <p>Sólo existe en el perfil {@code bench}: en un build normal ARC lo elimina y las rutas no
 * se registran. Un endpoint que duerme a petición del cliente es un DoS regalado, y además el
 * benchmark tiene que correr sobre un artefacto de producción, no en dev mode.
 */
@Path("/bench")
@Produces(MediaType.TEXT_PLAIN)
@IfBuildProfile("bench")
// Con deny-unannotated-endpoints activado (módulo 5), todo endpoint sin anotación queda
// denegado. El laboratorio se declara abierto explícitamente: mide concurrencia, y meter la
// validación de un token en el camino contaminaría la medición. Puede hacerlo porque solo
// existe en el perfil bench.
@PermitAll
public class SyntheticBenchmarkResource {

    /**
     * 100 ms es deliberado: se parece a una consulta que cruza la red y hace que la ley de
     * Little dé números redondos. Con 200 hilos de worker el techo teórico es 200 / 0,1 s =
     * 2.000 req/s, y ese número tiene que aparecer en las mediciones.
     */
    private static final Duration IO_LATENCY = Duration.ofMillis(100);

    /**
     * Modelo 1 — worker thread. Es lo que hace hoy {@code ListingResource}: Quarkus ve una
     * firma bloqueante en build time y despacha la petición al pool de workers.
     */
    @GET
    @Path("/blocking")
    public String blocking() throws InterruptedException {
        Thread.sleep(IO_LATENCY);
        return thread();
    }

    /**
     * Modelo 2 — virtual thread. El cuerpo es idéntico al anterior; lo único que cambia es
     * dónde corre. {@code Thread.sleep} sobre un hilo virtual no duerme un hilo del sistema
     * operativo: la JVM desmonta el hilo virtual de su carrier y lo vuelve a montar al
     * despertar.
     */
    @GET
    @Path("/virtual")
    @RunOnVirtualThread
    public String virtual() throws InterruptedException {
        Thread.sleep(IO_LATENCY);
        return thread();
    }

    /**
     * Modelo 3 — reactivo. Aquí no duerme nadie: {@code delayIt} programa un temporizador en
     * el event loop y devuelve el control inmediatamente. Entre la llamada y la respuesta no
     * hay ningún hilo retenido, sólo una continuación esperando en una cola.
     *
     * <p>El {@code item(() -> ...)} es perezoso a propósito: con {@code item(valor)} el nombre
     * del hilo se capturaría al construir el {@code Uni}, no al resolverlo, y la traza diría
     * el hilo equivocado.
     */
    @GET
    @Path("/reactive")
    public Uni<String> reactive() {
        return Uni.createFrom().item(SyntheticBenchmarkResource::thread)
                .onItem().delayIt().by(IO_LATENCY);
    }

    /**
     * EL BUG Nº 1, provocado a propósito: una firma que promete no bloquear sobre un cuerpo
     * que bloquea.
     *
     * <p>Quarkus ve {@code Uni} y clasifica el método como no bloqueante en build time, así
     * que lo ejecuta directamente sobre un event loop. Nadie comprueba que la promesa sea
     * cierta: el {@code Thread.sleep} de la línea siguiente deja el event loop parado.
     *
     * <p>Un event loop no atiende a una petición, atiende a miles. Pararlo no degrada esta
     * petición: degrada todas las conexiones que ese loop tenía asignadas, incluidas las de
     * endpoints impecablemente escritos. Con {@code 2 × núcleos} event loops, basta con
     * saturar unos pocos para tumbar el servidor entero.
     *
     * <p>En la práctica este bug casi nunca se escribe así de a la vista. Aparece cuando
     * alguien mete una llamada JDBC, un {@code RestClient} síncrono o un
     * {@code future.get()} dentro de un pipeline reactivo que ya existía.
     *
     * @param ms cuánto bloquear. Por defecto lo mismo que los demás endpoints, para que la
     *           comparación sea justa; con un valor por encima de 2000 salta además el aviso
     *           «Thread has been blocked» del detector de bloqueos de Vert.x.
     */
    @GET
    @Path("/lie")
    public Uni<String> lie(@QueryParam("ms") @DefaultValue("100") long ms)
            throws InterruptedException {
        Thread.sleep(ms);
        return Uni.createFrom().item(thread());
    }

    /**
     * LA CURA del bug anterior: el mismo cuerpo, más {@code @Blocking}.
     *
     * <p>La anotación corrige la clasificación que Quarkus dedujo del tipo de retorno. En vez
     * de ejecutarse sobre un event loop, la petición se despacha al pool de workers, donde
     * bloquear es legítimo: para eso está.
     *
     * <p>El resultado es contraintuitivo y merece pararse en él: marcar el endpoint como
     * <em>bloqueante</em> lo hace mucho MÁS rápido. No porque bloquear sea bueno, sino porque
     * el recurso escaso deja de ser 24 event loops y pasa a ser 200 workers. La anotación no
     * cambia el trabajo; cambia dónde se hace la cola.
     */
    @GET
    @Path("/fixed")
    @Blocking
    public Uni<String> fixed(@QueryParam("ms") @DefaultValue("100") long ms)
            throws InterruptedException {
        Thread.sleep(ms);
        return Uni.createFrom().item(thread());
    }

    /**
     * Devuelve el hilo que atiende la petición. Sirve de comprobación de que cada endpoint
     * corre donde creemos: {@code executor-thread-N}, {@code quarkus-virtual-thread-N} o
     * {@code vert.x-eventloop-thread-N}.
     */
    private static String thread() {
        return Thread.currentThread().toString();
    }
}
