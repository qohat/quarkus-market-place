package com.marketplace.shared.infrastructure.rest;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Comparator;
import java.util.List;

/**
 * Unifica los errores de Bean Validation con el resto de errores de la API.
 *
 * <p>Quarkus ya trae un mapper para esto, pero emite su propio formato
 * ({@code {"title": ..., "violations": [...]}}), distinto del RFC 7807 que usamos en todos los
 * demás errores. Un cliente tendría que saber leer dos formatos según qué falle, que es
 * exactamente lo que el estándar viene a evitar. Registrar el nuestro lo sustituye.
 *
 * <p>Detalle de usabilidad: {@code propertyPath} llega como
 * {@code createProduct.request.title}, con el nombre del método y del parámetro de por medio —
 * detalles de nuestra implementación que al cliente no le sirven de nada. Nos quedamos con el
 * último segmento, que es el campo que él escribió en el JSON.
 */
@Provider
public class ValidationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(ConstraintViolationException exception) {
        List<ProblemDetail.Violation> violations = exception.getConstraintViolations().stream()
                .map(ValidationExceptionMapper::toViolation)
                // Orden estable: sin esto, el orden depende del recorrido interno del validador
                // y las respuestas varían entre ejecuciones, lo que hace los tests intermitentes
                // y ensucia cualquier diff de contratos.
                .sorted(Comparator.comparing(ProblemDetail.Violation::field)
                        .thenComparing(ProblemDetail.Violation::message))
                .toList();

        return ProblemDetail.type("validation-failed")
                .title("Request validation failed")
                .status(Response.Status.BAD_REQUEST)
                .detail("The request has %d validation error(s)".formatted(violations.size()))
                .instance(uriInfo.getPath())
                .violations(violations)
                .build()
                .toResponse();
    }

    private static ProblemDetail.Violation toViolation(ConstraintViolation<?> violation) {
        return new ProblemDetail.Violation(lastSegmentOf(violation.getPropertyPath()),
                violation.getMessage());
    }

    private static String lastSegmentOf(Path propertyPath) {
        String last = null;
        for (Path.Node node : propertyPath) {
            last = node.getName();
        }
        return last == null ? "" : last;
    }
}
