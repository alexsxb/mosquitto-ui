package com.dfc.mosquittoui.localauth;

import com.dfc.mosquittoui.api.Dtos.ErrorResponse;
import com.dfc.mosquittoui.audit.Audited;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** The users of the local login, also while it is off (people of a provider can be mapped to them). Every one of them manages the broker. */
@Audited
@Path("/api/local-users")
public class LocalUserResource {

    public record LocalUserView(String username, Instant created, @Schema(nullable = true) Instant lastLogin, @Schema(description = "the user logged in") boolean self) {
    }

    public record NewLocalUser(String username, String password) {
    }

    public record PasswordInput(String password) {
    }

    @Inject
    LocalUsers users;

    @Inject
    LocalSessions sessions;

    @Inject
    SecurityIdentity identity;

    @Inject
    RoutingContext context;

    private boolean self(String username) {
        return identity.getPrincipal().getName().equalsIgnoreCase(username);
    }

    private LocalUserView view(LocalUsers.User user) {
        return new LocalUserView(user.username(), user.created(), user.lastLogin(), self(user.username()));
    }

    @GET
    public List<LocalUserView> listLocalUsers() {
        return users.all().stream().map(this::view).toList();
    }

    @POST
    public LocalUserView createLocalUser(NewLocalUser input) {
        return view(users.add(input.username() == null ? null : input.username().strip(), input.password()));
    }

    /** Ends the sessions of the user; for oneself a new session goes with the answer */
    @PUT
    @Path("/{username}/password")
    public LocalUserView setLocalUserPassword(@PathParam("username") String username, PasswordInput input) {
        LocalUsers.User user = users.setPassword(users.find(username).map(LocalUsers.User::username).orElse(username), input.password());
        if (self(username)) {
            Instant now = Instant.now();
            sessions.set(context, sessions.value(user.username(), user.version(), now, now));
        }
        return view(user);
    }

    @POST
    @Path("/{username}/end-sessions")
    public Response endLocalUserSessions(@PathParam("username") String username) {
        if (self(username)) {
            throw new LocalAuthException("self", "Log out instead", null);
        }
        users.endSessions(users.find(username).map(LocalUsers.User::username).orElse(username));
        return Response.noContent().build();
    }

    @DELETE
    @Path("/{username}")
    public Response deleteLocalUser(@PathParam("username") String username) {
        if (self(username)) {
            throw new LocalAuthException("self", "You cannot delete yourself", null);
        }
        users.delete(users.find(username).map(LocalUsers.User::username).orElse(username));
        return Response.noContent().build();
    }

    @ServerExceptionMapper
    public Response failed(LocalAuthException e) {
        Response.Status status = switch (e.code()) {
            case "validation", "password_length" -> Response.Status.BAD_REQUEST;
            case "not_found", "local_off" -> Response.Status.NOT_FOUND;
            default -> Response.Status.CONFLICT;
        };
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(new ErrorResponse(e.code(), e.getMessage(), e.field())).build();
    }
}
