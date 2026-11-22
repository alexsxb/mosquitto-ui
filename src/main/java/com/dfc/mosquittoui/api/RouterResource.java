package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.api.Dtos.RouterInfo;
import com.dfc.mosquittoui.router.RouterProbe;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

/** The internet connection as the router in the LAN tells it, for the port forwardings that connections from outside need. */
@Path("/api/router")
public class RouterResource {

    @Inject
    RouterProbe probe;

    @GET
    public RouterInfo getRouter(@QueryParam("host") String host) {
        return probe.probe(host == null || host.isBlank() ? "fritz.box" : host);
    }
}
