package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.audit.Audited;
import com.dfc.mosquittoui.inspect.InspectService;
import com.dfc.mosquittoui.inspect.InspectService.InspectStatus;
import com.dfc.mosquittoui.inspect.InspectService.Session;
import com.dfc.mosquittoui.inspect.InspectService.Trace;
import com.dfc.mosquittoui.inspect.InspectService.TraceInput;
import com.dfc.mosquittoui.inspect.InspectService.TraceView;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.util.List;

/** The look into the broker: its sessions, and recordings of what clients do (docker/mosquitto/inspect). */
@Audited
@Path("/api/inspect")
public class InspectResource {

    @Inject
    InspectService inspect;

    @Inject
    SecurityIdentity identity;

    /** Whether the inspect plugin can be asked. */
    @GET
    @Path("/status")
    public InspectStatus inspectStatus() {
        return inspect.status();
    }

    /** All sessions of the broker, connected or kept, with their subscriptions and messages. */
    @GET
    @Path("/sessions")
    public List<Session> listSessions() {
        return inspect.sessions();
    }

    /** The recordings kept, the latest first. */
    @GET
    @Path("/traces")
    public List<Trace> listTraces() {
        return inspect.traces();
    }

    /** Starts a recording; one that runs ends. */
    @POST
    @Path("/traces")
    public Trace startTrace(TraceInput input) {
        return inspect.start(input, identity.getPrincipal().getName());
    }

    /** A recording, with its events from the index after on. */
    @GET
    @Path("/traces/{id}")
    public TraceView getTrace(@PathParam("id") String id, @QueryParam("after") @DefaultValue("0") int after) {
        return inspect.view(id, after);
    }

    @POST
    @Path("/traces/{id}/stop")
    public Trace stopTrace(@PathParam("id") String id) {
        return inspect.stop(id);
    }

    @DELETE
    @Path("/traces/{id}")
    public void deleteTrace(@PathParam("id") String id) {
        inspect.delete(id);
    }
}
