package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.api.Dtos.BrokerStatsView;
import com.dfc.mosquittoui.api.Dtos.BrokerStatus;
import com.dfc.mosquittoui.api.Dtos.MissingClient;
import com.dfc.mosquittoui.api.Dtos.StatsSample;
import com.dfc.mosquittoui.broker.BrokerStats;
import com.dfc.mosquittoui.broker.ClientLogins;
import com.dfc.mosquittoui.broker.DynsecClient;
import com.dfc.mosquittoui.broker.DynsecService;
import com.dfc.mosquittoui.broker.MosquittoConfig;
import com.dfc.mosquittoui.tokenauth.TokenClientLogins;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Path("/api/broker")
public class BrokerResource {

    @Inject
    DynsecClient dynsec;

    @Inject
    MosquittoConfig config;

    @Inject
    BrokerStats stats;

    @Inject
    ClientLogins logins;

    @Inject
    DynsecService service;

    @Inject
    TokenClientLogins tokenLogins;

    @GET
    @Path("/status")
    public BrokerStatus status() {
        return new BrokerStatus(config.host(), config.port(), dynsec.isConnected(), dynsec.lastError());
    }

    @GET
    @Path("/stats")
    public BrokerStatsView stats() {
        return stats.current();
    }

    @GET
    @Path("/stats/history")
    public List<StatsSample> history(@QueryParam("hours") @DefaultValue("24") int hours) {
        return stats.history(Duration.ofHours(hours));
    }

    @GET
    @Path("/missing-clients")
    public List<MissingClient> missingClients() {
        Instant now = Instant.now();
        List<MissingClient> missing = new ArrayList<>(logins.missing(service.listClients(), now));
        missing.addAll(tokenLogins.missing(now));
        missing.sort(Comparator.comparing(MissingClient::lastSeen).reversed());
        return missing;
    }
}
