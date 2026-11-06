package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.logs.BrokerLogService;
import com.dfc.mosquittoui.logs.BrokerLogService.LogBatch;
import com.dfc.mosquittoui.logs.BrokerLogService.LogView;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;
import jakarta.ws.rs.QueryParam;

/** The log of the broker, as far as this application got it. */
@Path("/api/logs")
public class BrokerLogResource {

    @Inject
    BrokerLogService logs;

    /** The lines after the one with the id after, in the run known; all kept if the run is another one. */
    @GET
    public LogView brokerLog(@QueryParam("run") String run, @QueryParam("after") @DefaultValue("0") long after) {
        return logs.view(run, after);
    }

    /** All lines kept in the first events, then each new one as it comes; for the page Logs. */
    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<LogBatch> streamBrokerLog() {
        return logs.stream();
    }
}
