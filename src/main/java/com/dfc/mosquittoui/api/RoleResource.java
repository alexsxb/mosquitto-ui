package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.api.Dtos.Acl;
import com.dfc.mosquittoui.api.Dtos.RoleCreate;
import com.dfc.mosquittoui.api.Dtos.RoleUpdate;
import com.dfc.mosquittoui.api.Dtos.RoleView;
import com.dfc.mosquittoui.audit.Audited;
import com.dfc.mosquittoui.broker.DynsecService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.util.List;

@Audited
@Path("/api/roles")
public class RoleResource {

    @Inject
    DynsecService dynsec;

    @GET
    public List<RoleView> listRoles() {
        return dynsec.listRoles();
    }

    @POST
    public void createRole(@Valid RoleCreate role) {
        dynsec.createRole(role);
    }

    @PUT
    @Path("/{rolename}")
    public void updateRole(@PathParam("rolename") String rolename, @Valid RoleUpdate role) {
        dynsec.updateRole(rolename, role);
    }

    @DELETE
    @Path("/{rolename}")
    public void deleteRole(@PathParam("rolename") String rolename) {
        dynsec.deleteRole(rolename);
    }

    @POST
    @Path("/{rolename}/acls")
    public void addRoleAcl(@PathParam("rolename") String rolename, @Valid Acl acl) {
        dynsec.addRoleAcl(rolename, acl);
    }

    @DELETE
    @Path("/{rolename}/acls")
    public void removeRoleAcl(@PathParam("rolename") String rolename, @QueryParam("acltype") @NotBlank String acltype,
            @QueryParam("topic") @NotBlank String topic) {
        dynsec.removeRoleAcl(rolename, acltype, topic);
    }
}
