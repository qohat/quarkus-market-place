package com.marketplace.shared.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Cuerpo de error según <strong>RFC 7807 (Problem Details for HTTP APIs)</strong>.
 *
 * <p>Antes de este estándar, cada API inventaba su propio formato de error: unas devolvían
 * {@code {"error": "..."}}, otras {@code {"message": ...}}, otras HTML. El RFC fija un
 * vocabulario mínimo para que cualquier cliente sepa leer el error sin documentación previa.
 *
 * <ul>
 *   <li>{@code type} — URI que identifica <em>la clase</em> de problema. Es el campo sobre el
 *       que un cliente debe ramificar, porque es estable; el texto de {@code title} puede
 *       reescribirse sin previo aviso.</li>
 *   <li>{@code title} — resumen legible, igual para todas las ocurrencias del mismo tipo.</li>
 *   <li>{@code status} — el código HTTP, repetido en el cuerpo para que sobreviva a proxies y
 *       a logs que solo guardan el payload.</li>
 *   <li>{@code detail} — explicación de <em>esta</em> ocurrencia concreta.</li>
 *   <li>{@code instance} — URI de la petición que falló.</li>
 * </ul>
 *
 * <p>El RFC permite extensiones, y añadimos una: {@code violations}, para el detalle campo a
 * campo de los errores de validación.
 *
 * <p>Se sirve con el media type {@value #MEDIA_TYPE}, no con {@code application/json}. Así un
 * cliente distingue por la cabecera si le llegó el recurso que pedía o una descripción de por
 * qué no.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemDetail(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        List<Violation> violations
) {

    public static final String MEDIA_TYPE = "application/problem+json";

    /** Espacio de nombres de los tipos de problema de esta API. */
    private static final String BASE_TYPE = "https://marketplace.local/problems/";

    /** Un error concreto sobre un campo concreto. */
    public record Violation(String field, String message) {}

    public static Builder type(String slug) {
        return new Builder(BASE_TYPE + slug);
    }

    /**
     * Construye la respuesta HTTP completa, con su código y su media type.
     *
     * <p>Que el propio problema sepa convertirse en {@code Response} evita que cada
     * {@code ExceptionMapper} repita las mismas tres líneas y se olvide del media type en alguna.
     */
    public Response toResponse() {
        return Response.status(status)
                .type(MEDIA_TYPE)
                .entity(this)
                .build();
    }

    public static final class Builder {

        private final String type;
        private String title;
        private int status;
        private String detail;
        private String instance;
        private List<Violation> violations;

        private Builder(String type) {
            this.type = type;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder status(Response.Status status) {
            this.status = status.getStatusCode();
            return this;
        }

        public Builder detail(String detail) {
            this.detail = detail;
            return this;
        }

        public Builder instance(String instance) {
            this.instance = instance;
            return this;
        }

        public Builder violations(List<Violation> violations) {
            this.violations = violations == null || violations.isEmpty() ? null : violations;
            return this;
        }

        public ProblemDetail build() {
            return new ProblemDetail(type, title, status, detail, instance, violations);
        }
    }
}
