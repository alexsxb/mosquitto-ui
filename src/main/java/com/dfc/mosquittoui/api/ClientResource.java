package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.api.Dtos.ClientCreate;
import com.dfc.mosquittoui.api.Dtos.ClientUpdate;
import com.dfc.mosquittoui.api.Dtos.ClientView;
import com.dfc.mosquittoui.api.Dtos.EnabledChange;
import com.dfc.mosquittoui.api.Dtos.PasswordChange;
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
@Path("/api/clients")
public class ClientResource {

    @Inject
    DynsecService dynsec;

    @GET
    public List<ClientView> listClients() {
        return dynsec.listClients();
    }

    @GET
    @Path("/{username}")
    public ClientView getClient(@PathParam("username") String username) {
        return dynsec.getClient(username);
    }

    @POST
    public void createClient(@Valid ClientCreate client) {
        dynsec.createClient(client);
    }

    @PUT
    @Path("/{username}")
    public void updateClient(@PathParam("username") String username, @Valid ClientUpdate client) {
        dynsec.updateClient(username, client);
    }

    @PUT
    @Path("/{username}/password")
    public void setClientPassword(@PathParam("username") String username, @Valid PasswordChange change) {
        dynsec.setClientPassword(username, change.password());
    }

    @PUT
    @Path("/{username}/enabled")
    public void setClientEnabled(@PathParam("username") String username, @Valid EnabledChange change) {
        dynsec.setClientEnabled(username, change.enabled());
    }

    @POST
    @Path("/{username}/disconnect")
    public void disconnectClient(@PathParam("username") String username) {
        dynsec.disconnectClient(username);
    }

    @DELETE
    @Path("/{username}")
    public void deleteClient(@PathParam("username") String username) {
        dynsec.deleteClient(username);
    }
}
