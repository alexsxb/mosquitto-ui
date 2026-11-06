package com.dfc.mosquittoui.sso;

import com.dfc.mosquittoui.api.Dtos.ErrorResponse;
import com.dfc.mosquittoui.audit.Audited;
import com.dfc.mosquittoui.sso.SsoDiscovery.Discovered;
import com.dfc.mosquittoui.sso.SsoService.ProviderInput;
import com.dfc.mosquittoui.sso.SsoService.ProviderView;
import com.dfc.mosquittoui.sso.SsoService.SsoSettings;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** The page Login: identity providers and the local login. */
@Audited
@Path("/api/sso")
public class SsoResource {

    public record LocalLoginInput(boolean enabled) {
    }

    public record AllowInput(String match, @Schema(nullable = true) String localUser) {
    }

    public record IssuerInput(String issuer) {
    }

    @Inject
    SsoService sso;

    @Inject
    SsoDiscovery discovery;

    @GET
    public SsoSettings getSsoSettings() {
        return sso.settings();
    }

    @PUT
    @Path("/local-login")
    public SsoSettings setLocalLogin(LocalLoginInput input) {
        return sso.setLocalLogin(input != null && input.enabled());
    }

    @POST
    @Path("/providers")
    public ProviderView createProvider(ProviderInput input) {
        return sso.create(input);
    }

    @PUT
    @Path("/providers/{id}")
    public ProviderView updateProvider(@PathParam("id") String id, ProviderInput input) {
        return sso.update(id, input);
    }

    @DELETE
    @Path("/providers/{id}")
    public Response deleteProvider(@PathParam("id") String id) {
        sso.delete(id);
        return Response.noContent().build();
    }

    /** Lets in somebody who was turned away */
    @POST
    @Path("/providers/{id}/allow")
    public ProviderView allowAtProvider(@PathParam("id") String id, AllowInput input) {
        return sso.allow(id, input == null ? null : input.match(), input == null ? null : input.localUser());
    }

    @DELETE
    @Path("/providers/{id}/denied")
    public ProviderView clearDenied(@PathParam("id") String id) {
        return sso.clearDenied(id);
    }

    /** Whether the address answers as an OpenID Connect provider; changes nothing */
    @POST
    @Path("/discover")
    public Discovered discoverIssuer(IssuerInput input) {
        if (input == null || input.issuer() == null || !input.issuer().strip().matches("https?://\\S+")) {
            return new Discovered(false, null, null, null, "An http(s) address");
        }
        return discovery.discover(input.issuer().strip());
    }

    @ServerExceptionMapper
    public Response failed(SsoException e) {
        Response.Status status = switch (e.code()) {
            case "validation" -> Response.Status.BAD_REQUEST;
            case "not_found" -> Response.Status.NOT_FOUND;
            default -> Response.Status.CONFLICT;
        };
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(new ErrorResponse(e.code(), e.getMessage(), e.field())).build();
    }
}
