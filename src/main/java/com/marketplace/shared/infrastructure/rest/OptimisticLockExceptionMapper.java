package com.marketplace.shared.infrastructure.rest;

import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Traduce un conflicto de bloqueo optimista a <strong>409 Conflict</strong>.
 *
 * <p>Es el código correcto y no un 500, porque no ha fallado nada: dos peticiones legítimas
 * intentaron modificar el mismo recurso a la vez y una llegó tarde. El servidor está sano.
 *
 * <p>La diferencia importa para el cliente. Un 500 le dice "algo se rompió, mejor no reintentes".
 * Un 409 le dice "vuelve a leer el recurso y reintenta", que es exactamente la acción correcta —
 * y con conflictos poco frecuentes, un reintento inmediato casi siempre funciona.
 *
 * <p>Se registra en DEBUG y no en WARN: bajo concurrencia normal, estos conflictos son esperables
 * y ocurren de forma rutinaria. Llenar el log de warnings por algo que el sistema maneja bien
 * solo consigue que nadie mire los warnings de verdad.
 */
@Provider
public class OptimisticLockExceptionMapper implements ExceptionMapper<OptimisticLockException> {

    private static final Logger LOG = Logger.getLogger(OptimisticLockExceptionMapper.class);

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(OptimisticLockException exception) {
        LOG.debugf("Conflicto de concurrencia en %s", uriInfo.getPath());

        return ProblemDetail.type("concurrent-modification")
                .title("The resource was modified by someone else")
                .status(Response.Status.CONFLICT)
                .detail("Another request updated this listing first. "
                        + "Re-read the resource and try again.")
                .instance(uriInfo.getPath())
                .build()
                .toResponse();
    }
}
