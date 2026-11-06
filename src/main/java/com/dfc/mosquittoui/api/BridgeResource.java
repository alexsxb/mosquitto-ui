package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.api.Dtos.BridgeForm;
import com.dfc.mosquittoui.api.Dtos.BridgesView;
import com.dfc.mosquittoui.api.Dtos.EnabledChange;
import com.dfc.mosquittoui.api.Dtos.VictronPairing;
import com.dfc.mosquittoui.audit.Audited;
import com.dfc.mosquittoui.config.BridgeService;
import com.dfc.mosquittoui.victron.VictronService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

@Audited
@Path("/api/bridges")
public class BridgeResource {

    @Inject
    BridgeService bridges;

    @Inject
    VictronService victron;

    @GET
    public BridgesView listBridges() {
        return bridges.view();
    }

    @POST
    public void createBridge(@Valid BridgeForm bridge) {
        bridges.create(bridge);
    }

    /** Pairs with a GX device and adds the bridge to it. */
    @POST
    @Path("/victron")
    public void pairVictron(@Valid VictronPairing pairing) {
        victron.pair(pairing);
    }

    /** Pairs a bridge to a GX device again, in pairing mode: a new token and the certificate it presents now, for example after a reset. */
    @POST
    @Path("/{name}/victron/pair-again")
    public void pairVictronAgain(@PathParam("name") String name) {
        victron.pairAgain(name);
    }

    /** Answers restart_required unless confirmRestart is set: a change only takes effect after a restart, which disconnects all clients. */
    @PUT
    @Path("/{name}")
    public void updateBridge(@PathParam("name") String name, @QueryParam("confirmRestart") boolean confirmRestart, @Valid BridgeForm bridge) {
        bridges.update(name, bridge, confirmRestart);
    }

    /** Stops or starts the bridge without a restart of the broker. */
    @PUT
    @Path("/{name}/enabled")
    public void setBridgeEnabled(@PathParam("name") String name, EnabledChange change) {
        bridges.setEnabled(name, change.enabled());
    }

    @DELETE
    @Path("/{name}")
    public void deleteBridge(@PathParam("name") String name) {
        bridges.delete(name);
    }
}
