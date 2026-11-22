package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.certificate.Http01Tokens;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** HTTP-01: the CA asks here, without a login, on port 80 of each domain, which the proxy or router has to pass on to the UI. */
@Path("/.well-known/acme-challenge")
public class AcmeChallengeResource {

    @Inject
    Http01Tokens tokens;

    @GET
    @Path("/{token}")
    @Produces(MediaType.TEXT_PLAIN)
    public String answer(@PathParam("token") String token) {
        String answer = tokens.answer(token);
        if (answer == null) {
            throw new NotFoundException();
        }
        return answer;
    }
}
