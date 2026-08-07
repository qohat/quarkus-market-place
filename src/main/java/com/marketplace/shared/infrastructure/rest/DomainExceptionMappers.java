package com.marketplace.shared.infrastructure.rest;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Traducción a HTTP de las excepciones que lanza el dominio al defender sus invariantes.
 *
 * <h2>El trade-off que hay que conocer</h2>
 *
 * Estos mappers capturan {@link IllegalArgumentException} e {@link IllegalStateException}, que
 * son excepciones <em>de la biblioteca estándar</em>. Eso significa que también capturan las que
 * lance cualquier librería de terceros por un bug nuestro, y las convierten en 4xx.
 *
 * <p>El riesgo es real: un fallo interno que debería ser un 500 —y despertar a alguien— se
 * presenta como "petición incorrecta" y le echa la culpa al cliente. Peor aún, desaparece de las
 * métricas de error del servidor.
 *
 * <p>Se mitiga registrando la excepción completa en WARN, para que quede rastro aunque la
 * respuesta diga 4xx. Pero la mitigación no es la solución.
 *
 * <p><strong>La solución correcta</strong> en un sistema maduro es que el dominio lance
 * excepciones propias ({@code InvalidPriceException}, {@code InvalidTransitionException}) y que
 * los mappers cubran solo esas, dejando que todo lo demás sea un 500 honesto. Lo hacemos así
 * aquí por concisión, pero es una deuda consciente, no un patrón a copiar.
 */
public final class DomainExceptionMappers {

    private static final Logger LOG = Logger.getLogger(DomainExceptionMappers.class);

    private DomainExceptionMappers() {
    }

    /**
     * Invariante de dominio violado por el contenido de la petición: precio no positivo, escala
     * mayor que la de la moneda, cantidad fuera de rango.
     *
     * <p>Es un 400 y no un 422 porque la distinción entre "sintácticamente inválido" y
     * "semánticamente inválido" no la respeta casi ningún cliente, y 400 es lo que todos
     * entienden. Mantenerlo simple aquí vale más que la pureza.
     */
    @Provider
    public static class IllegalArgument implements ExceptionMapper<IllegalArgumentException> {

        @Context
        UriInfo uriInfo;

        @Override
        public Response toResponse(IllegalArgumentException exception) {
            LOG.warnf(exception, "Petición rechazada en %s", uriInfo.getPath());

            return ProblemDetail.type("invalid-request")
                    .title("Invalid request")
                    .status(Response.Status.BAD_REQUEST)
                    .detail(exception.getMessage())
                    .instance(uriInfo.getPath())
                    .build()
                    .toResponse();
        }
    }

    /**
     * La petición es válida, pero el recurso está en un estado que no admite esa operación:
     * publicar algo archivado, por ejemplo.
     *
     * <p><strong>409 Conflict</strong>, no 400. La diferencia importa para el cliente: un 400
     * significa "arregla la petición", mientras que un 409 significa "la petición está bien, es
     * el estado del servidor el que no encaja" — y a veces basta con recargar y reintentar.
     */
    @Provider
    public static class IllegalState implements ExceptionMapper<IllegalStateException> {

        @Context
        UriInfo uriInfo;

        @Override
        public Response toResponse(IllegalStateException exception) {
            LOG.warnf(exception, "Conflicto de estado en %s", uriInfo.getPath());

            return ProblemDetail.type("invalid-state-transition")
                    .title("Operation not allowed in the current state")
                    .status(Response.Status.CONFLICT)
                    .detail(exception.getMessage())
                    .instance(uriInfo.getPath())
                    .build()
                    .toResponse();
        }
    }
}
