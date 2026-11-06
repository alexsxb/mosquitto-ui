package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.api.Dtos.GroupCreate;
import com.dfc.mosquittoui.api.Dtos.GroupUpdate;
import com.dfc.mosquittoui.api.Dtos.GroupView;
import com.dfc.mosquittoui.audit.Audited;
import com.dfc.mosquittoui.broker.DynsecService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.util.List;

@Audited
@Path("/api/groups")
public class GroupResource {

    @Inject
    DynsecService dynsec;

    @GET
    public List<GroupView> listGroups() {
        return dynsec.listGroups();
    }

    @POST
    public void createGroup(@Valid GroupCreate group) {
        dynsec.createGroup(group);
    }

    @PUT
    @Path("/{groupname}")
    public void updateGroup(@PathParam("groupname") String groupname, @Valid GroupUpdate group) {
        dynsec.updateGroup(groupname, group);
    }

    @DELETE
    @Path("/{groupname}")
    public void deleteGroup(@PathParam("groupname") String groupname) {
        dynsec.deleteGroup(groupname);
    }
}
