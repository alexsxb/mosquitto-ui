package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.api.Dtos.AuditEntry;
import com.dfc.mosquittoui.audit.AuditLog;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import java.util.List;

@Path("/api/audit")
public class AuditResource {

    @Inject
    AuditLog log;

    @GET
    public List<AuditEntry> auditLog(@QueryParam("limit") @DefaultValue("1000") int limit) {
        return log.latest(limit);
    }
}
