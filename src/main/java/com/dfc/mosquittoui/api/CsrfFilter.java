package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.api.Dtos.ErrorResponse;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Set;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

/**
 * Protection against requests from other sites (CSRF). The session cookie goes along with every request the browser sends
 * to this host, also one that a page on another subdomain of the same site makes, and a form upload needs no CORS
 * preflight. A changing request therefore has to carry X-Requested-With: another site can only set the header after a
 * preflight, which this server never allows, as it has no CORS configuration. The frontend sends it with every request.
 */
public class CsrfFilter {

    static final String HEADER = "X-Requested-With";

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    @ServerRequestFilter
    public Response requireHeader(ContainerRequestContext request) {
        // only the API works with the session; other resources (in the tests the fake DNS and GX servers) are none of its business
        if (SAFE.contains(request.getMethod()) || !request.getUriInfo().getPath().startsWith("/api/")) {
            return null;
        }
        String value = request.getHeaderString(HEADER);
        if (value != null && !value.isBlank()) {
            return null;
        }
        return Response.status(Response.Status.FORBIDDEN).type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse("csrf", "Request without " + HEADER + ", refused", null)).build();
    }
}
