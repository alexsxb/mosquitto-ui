package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.audit.Audited;
import com.dfc.mosquittoui.audit.NotAudited;
import com.dfc.mosquittoui.tokenauth.TokenAuthService;
import com.dfc.mosquittoui.tokenauth.TokenAuthService.IssueInput;
import com.dfc.mosquittoui.tokenauth.TokenAuthService.IssueResult;
import com.dfc.mosquittoui.tokenauth.TokenAuthService.TokenAuthInput;
import com.dfc.mosquittoui.tokenauth.TokenAuthService.TokenAuthSettings;
import com.dfc.mosquittoui.tokenauth.TokenAuthService.TokenBlockInput;
import com.dfc.mosquittoui.tokenauth.TokenAuthService.TokenClient;
import com.dfc.mosquittoui.tokenauth.TokenAuthService.TokenRejection;
import com.dfc.mosquittoui.tokenauth.TokenClientLogins;
import com.dfc.mosquittoui.tokenauth.TokenClientLogins.SeenTokenClient;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.util.List;

/** Login of MQTT clients with an access token: identity providers, their groups and roles, and the keys last fetched. */
@Audited
@Path("/api/token-auth")
public class TokenAuthResource {

    @Inject
    TokenAuthService tokenAuth;

    @Inject
    TokenClientLogins logins;

    @Inject
    SecurityIdentity identity;

    @GET
    public TokenAuthSettings getTokenAuth() {
        return tokenAuth.get();
    }

    @PUT
    public TokenAuthSettings updateTokenAuth(TokenAuthInput input) {
        return tokenAuth.update(input);
    }

    /** Blocks a user name, subject or single token; clients it holds for are disconnected at once. */
    @POST
    @Path("/blocks")
    public TokenAuthSettings addTokenBlock(TokenBlockInput input) {
        return tokenAuth.block(input, identity.getPrincipal().getName());
    }

    @DELETE
    @Path("/blocks/{id}")
    public TokenAuthSettings removeTokenBlock(@PathParam("id") String id) {
        return tokenAuth.unblock(id);
    }

    /** Signs a token for a client; the answer holds it this once. */
    @POST
    @Path("/issued")
    public IssueResult issueToken(IssueInput input) {
        return tokenAuth.issue(input, identity.getPrincipal().getName());
    }

    /** Other roles for a token handed out, at once also for clients connected with it. */
    @PUT
    @Path("/issued/{id}")
    public TokenAuthSettings updateIssuedToken(@PathParam("id") String id, List<String> roles) {
        return tokenAuth.changeIssued(id, roles);
    }

    /** Takes a token back; clients connected with it are disconnected. */
    @DELETE
    @Path("/issued/{id}")
    public TokenAuthSettings revokeIssuedToken(@PathParam("id") String id) {
        return tokenAuth.revokeIssued(id);
    }

    /** The clients connected with a token now. */
    @GET
    @Path("/clients")
    public List<TokenClient> listTokenClients() {
        return tokenAuth.clients();
    }

    /** All user names that connected with a token and were not hidden, with their last connection; those connected now first. */
    @GET
    @Path("/seen")
    public List<SeenTokenClient> listSeenTokenClients() {
        return logins.seen();
    }

    /** Hides a user name in the list of those seen until it connects again; it is no longer missed either. */
    @DELETE
    @Path("/seen/{username}")
    public List<SeenTokenClient> hideSeenTokenClient(@PathParam("username") String username) {
        try {
            if (!logins.hide(username)) {
                throw new NotFoundException(username);
            }
        } catch (IllegalStateException e) {
            throw new ClientErrorException(e.getMessage(), Response.Status.CONFLICT);
        }
        return logins.seen();
    }

    /** The tokens the broker did not accept since it started, the latest first. */
    @GET
    @Path("/rejected")
    public List<TokenRejection> listTokenRejections() {
        return tokenAuth.rejected();
    }

    /** Fetches the keys of all identity providers again, as after a rotation. */
    @NotAudited
    @POST
    @Path("/refresh")
    public TokenAuthSettings refreshTokenAuth() {
        return tokenAuth.refreshAll();
    }
}
