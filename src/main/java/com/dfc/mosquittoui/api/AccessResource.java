package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.api.Dtos.AccessDefaults;
import com.dfc.mosquittoui.api.Dtos.AccessView;
import com.dfc.mosquittoui.api.Dtos.AclTest;
import com.dfc.mosquittoui.api.Dtos.AclTestResult;
import com.dfc.mosquittoui.api.Dtos.AnonymousGroup;
import com.dfc.mosquittoui.audit.Audited;
import com.dfc.mosquittoui.audit.NotAudited;
import com.dfc.mosquittoui.broker.AclTester;
import com.dfc.mosquittoui.broker.DynsecService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;

/** What holds for all clients, and what a client may do. */
@Audited
@Path("/api/access")
public class AccessResource {

    @Inject
    DynsecService dynsec;

    @Inject
    AclTester tester;

    @GET
    public AccessView getAccess() {
        return dynsec.access();
    }

    @PUT
    @Path("/defaults")
    public void setDefaultAccess(@Valid AccessDefaults defaults) {
        dynsec.setDefaultAccess(defaults);
    }

    @PUT
    @Path("/anonymous-group")
    public void setAnonymousGroup(@Valid AnonymousGroup group) {
        dynsec.setAnonymousGroup(group.groupname());
    }

    @NotAudited
    @POST
    @Path("/test")
    public AclTestResult testAccess(@Valid AclTest test) {
        return tester.test(test);
    }
}
