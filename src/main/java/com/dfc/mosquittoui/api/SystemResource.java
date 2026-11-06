package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.api.Dtos.SystemInfo;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Basic information about this application. */
@Path("/api/system")
public class SystemResource {

    @ConfigProperty(name = "quarkus.application.name")
    String name;

    @ConfigProperty(name = "quarkus.application.version")
    String version;

    @GET
    @Path("/info")
    public SystemInfo info() {
        return new SystemInfo(name, version);
    }
}
