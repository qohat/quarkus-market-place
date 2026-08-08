package com.marketplace.catalog.infrastructure.rest;

import com.marketplace.catalog.domain.NotTheOwnerException;
import com.marketplace.shared.infrastructure.rest.ProblemDetail;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Traduce la excepción de dominio a un 403.
 *
 * <h2>Por qué 403 y no 404</h2>
 *
 * Un 404 sería más hermético: al no distinguirse de un identificador inexistente, impide que
 * alguien enumere qué recursos existen probando ids. Aquí se elige 403 porque nuestras
 * publicaciones <strong>ya son públicas</strong> vía {@code GET /listings/{id}}: fingir que no
 * existe no oculta nada que no se pueda comprobar con otra petición, y a cambio le da al dueño
 * legítimo un mensaje incomprensible cuando se equivoca de cuenta.
 *
 * <p>La regla general es la contraria: cuando el recurso también es privado para lectura —los
 * pedidos del módulo 6, por ejemplo— responder 404 y no confirmar ni siquiera su existencia.
 *
 * <h2>Por qué el detalle que se devuelve dice menos que la excepción</h2>
 *
 * La excepción lleva quién pidió qué, porque eso hace falta para investigar. La respuesta HTTP
 * no repite el identificador del solicitante: quien ataca ya sabe con qué cuenta va, y devolverle
 * datos internos solo le confirma que el sistema los maneja. Al log va todo; al cliente, lo justo.
 */
@Provider
public class NotTheOwnerExceptionMapper implements ExceptionMapper<NotTheOwnerException> {

    private static final Logger LOG = Logger.getLogger(NotTheOwnerExceptionMapper.class);

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(NotTheOwnerException exception) {
        // Un intento de tocar lo ajeno es una señal de seguridad, no una incidencia de negocio:
        // se registra en WARN para que sea visible si alguien empieza a probar identificadores.
        LOG.warnf("Denied: seller %s tried to modify listing %s",
                exception.requester(), exception.listingId());

        return ProblemDetail.type("not-the-owner")
                .title("Not the owner")
                .status(Response.Status.FORBIDDEN)
                .detail("This listing belongs to another seller")
                .instance(uriInfo.getPath())
                .build()
                .toResponse();
    }
}
