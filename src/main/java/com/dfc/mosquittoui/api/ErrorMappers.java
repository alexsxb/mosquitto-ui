package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.api.Dtos.ErrorResponse;
import com.dfc.mosquittoui.backup.BackupException;
import com.dfc.mosquittoui.broker.BrokerUnavailableException;
import com.dfc.mosquittoui.broker.DynsecException;
import com.dfc.mosquittoui.broker.LockedException;
import com.dfc.mosquittoui.certificate.CertificateException;
import com.dfc.mosquittoui.config.AgentException;
import com.dfc.mosquittoui.config.BridgeException;
import com.dfc.mosquittoui.importer.InvalidFileException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Error responses as ErrorResponse, the frontend translates them by their code. */
public class ErrorMappers {

    private static Response error(Response.Status status, String code, String message, String field) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(new ErrorResponse(code, message, field)).build();
    }

    @ServerExceptionMapper
    public Response brokerUnavailable(BrokerUnavailableException e) {
        return error(Response.Status.SERVICE_UNAVAILABLE, "broker_unavailable", e.getMessage(), null);
    }

    @ServerExceptionMapper
    public Response locked(LockedException e) {
        return error(Response.Status.CONFLICT, "locked", e.getMessage(), null);
    }

    /** The plugin has no error codes, only texts like "Client not found" and "Role already exists". */
    @ServerExceptionMapper
    public Response dynsec(DynsecException e) {
        String message = e.getMessage();
        if (message.endsWith("not found")) {
            return error(Response.Status.NOT_FOUND, "not_found", message, null);
        }
        if (message.endsWith("already exists")) {
            return error(Response.Status.CONFLICT, "already_exists", message, null);
        }
        return error(Response.Status.BAD_REQUEST, "broker_rejected", message, null);
    }

    @ServerExceptionMapper
    public Response agent(AgentException e) {
        return e.unavailable()
                ? error(Response.Status.SERVICE_UNAVAILABLE, "agent_unavailable", e.getMessage(), null)
                : error(Response.Status.BAD_REQUEST, "config_rejected", e.getMessage(), null);
    }

    @ServerExceptionMapper
    public Response certificate(CertificateException e) {
        return error(Response.Status.CONFLICT, e.code(), e.getMessage(), null);
    }

    @ServerExceptionMapper
    public Response backup(BackupException e) {
        Response.Status status = switch (e.code()) {
            case "validation" -> Response.Status.BAD_REQUEST;
            case "not_found" -> Response.Status.NOT_FOUND;
            default -> Response.Status.CONFLICT;
        };
        return error(status, e.code(), e.getMessage(), e.field());
    }

    @ServerExceptionMapper
    public Response bridge(BridgeException e) {
        return error(e.code().equals("not_found") ? Response.Status.NOT_FOUND : Response.Status.CONFLICT, e.code(), e.getMessage(), null);
    }

    @ServerExceptionMapper
    public Response invalidFile(InvalidFileException e) {
        return error(Response.Status.BAD_REQUEST, "invalid_file", e.getMessage(), null);
    }

    @ServerExceptionMapper
    public Response validation(ConstraintViolationException e) {
        ConstraintViolation<?> violation = e.getConstraintViolations().iterator().next();
        String path = violation.getPropertyPath().toString();
        String field = path.substring(path.lastIndexOf('.') + 1);
        return error(Response.Status.BAD_REQUEST, "validation", field + ": " + violation.getMessage(), field);
    }
}
