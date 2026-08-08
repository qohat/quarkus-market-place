package com.marketplace.benchmark;

import io.quarkus.arc.profile.IfBuildProfile;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Segundo escenario: los tres modelos de concurrencia contra PostgreSQL de verdad.
 *
 * <p>En el laboratorio sintético lo único escaso eran los hilos, y ahí virtual y reactivo
 * multiplicaban por diez al modelo bloqueante. Aquí aparece un segundo recurso escaso —el
 * pool de conexiones, 20 por defecto— y la pregunta del módulo es si esa ventaja sobrevive.
 *
 * <h2>Por qué la consulta es {@code pg_sleep} y no una de verdad</h2>
 *
 * Una consulta real mezcla en el mismo número el modelo de concurrencia, el plan de ejecución,
 * la caché de PostgreSQL, el mapeo del ORM y la serialización. Aquí queremos aislar el modelo
 * de concurrencia y el pool, así que la consulta simula 10 ms de trabajo en el servidor de
 * base de datos: mantiene la conexión ocupada exactamente ese tiempo, que es lo único que
 * importa para la aritmética del pool.
 *
 * <p>Con 20 conexiones y 10 ms por consulta, la ley de Little fija el techo en
 * {@code 20 / 0,01 s = 2.000 req/s}, y ese techo debería aplicarse a los TRES modelos por
 * igual. Esa es la hipótesis.
 *
 * <h2>Por qué el modelo reactivo no usa Hibernate</h2>
 *
 * No puede. Hibernate ORM es bloqueante de raíz: su API devuelve entidades, no {@code Uni}.
 * El modelo reactivo obliga a cambiar de pila de persistencia —cliente PgPool y SQL a mano—
 * y a renunciar al mapeo, a la caché de primer nivel y a {@code @Transactional}. Ese coste
 * no aparece en ninguna gráfica de rendimiento, pero es el que decide de verdad si merece
 * la pena.
 */
@Path("/bench/db")
@Produces(MediaType.TEXT_PLAIN)
@IfBuildProfile("bench")
public class DatabaseBenchmarkResource {

    /** 10 ms de trabajo en el servidor de base de datos, sin devolver datos que serializar. */
    private static final String CONSULTA = "select pg_sleep(0.01)";

    /** Pila bloqueante: Agroal, el pool JDBC que ya usa Hibernate. */
    private final DataSource dataSource;

    /** Pila reactiva: pool propio, protocolo no bloqueante sobre el event loop. */
    private final PgPool pgPool;

    public DatabaseBenchmarkResource(DataSource dataSource, PgPool pgPool) {
        this.dataSource = dataSource;
        this.pgPool = pgPool;
    }

    /**
     * Modelo 1 — worker thread. El hilo se queda retenido durante toda la consulta, y además
     * ocupa una conexión: dos recursos escasos a la vez.
     */
    @GET
    @Path("/blocking")
    public String blocking() throws SQLException {
        return consultaBloqueante();
    }

    /**
     * Modelo 2 — virtual thread. Mismo código. La diferencia es que el hilo ya no es escaso;
     * la conexión sigue siéndolo. Si la hipótesis es cierta, esto no aporta nada aquí.
     *
     * <p>Atención al detalle de Loom: mientras el driver JDBC espera la respuesta del socket,
     * la JVM puede desmontar el hilo virtual. Pero la CONEXIÓN no se libera hasta que la
     * consulta termina, así que el pool no se entera de nada.
     */
    @GET
    @Path("/virtual")
    @RunOnVirtualThread
    public String virtual() throws SQLException {
        return consultaBloqueante();
    }

    /**
     * Modelo 3 — reactivo. Ni hilo retenido ni bloqueo: la conexión se pide, se usa y se
     * devuelve al pool mediante callbacks. Pero sigue siendo una de las 20.
     */
    @GET
    @Path("/reactive")
    public Uni<String> reactive() {
        return pgPool.preparedQuery(CONSULTA).execute()
                .map(filas -> "reactive " + Thread.currentThread());
    }

    /**
     * try-with-resources cierra el {@code Connection}, que en un pool no lo cierra de verdad:
     * lo devuelve. Olvidarlo es la forma más rápida de agotar el pool y dejar la aplicación
     * colgada sin un solo error en los logs.
     */
    private String consultaBloqueante() throws SQLException {
        try (var conexion = dataSource.getConnection();
             var sentencia = conexion.prepareStatement(CONSULTA)) {
            sentencia.execute();
            return "blocking " + Thread.currentThread();
        }
    }
}
