package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.api.Dtos.ConfigApplied;
import com.dfc.mosquittoui.api.Dtos.ConfigCheck;
import com.dfc.mosquittoui.api.Dtos.ConfigForm;
import com.dfc.mosquittoui.api.Dtos.ConfigText;
import com.dfc.mosquittoui.api.Dtos.ConfigView;
import com.dfc.mosquittoui.api.Dtos.ResetOutcome;
import com.dfc.mosquittoui.api.Dtos.ResetRequest;
import com.dfc.mosquittoui.audit.Audited;
import com.dfc.mosquittoui.audit.NotAudited;
import com.dfc.mosquittoui.config.ConfigService;
import com.dfc.mosquittoui.importer.ResetService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;

@Audited
@Path("/api/config")
public class ConfigResource {

    @Inject
    ConfigService config;

    @Inject
    ResetService reset;

    @GET
    public ConfigView getConfig() {
        return config.view();
    }

    /** What applying this text would change. */
    @NotAudited
    @POST
    @Path("/check")
    public ConfigCheck checkConfig(@Valid ConfigText text) {
        return config.check(text.text());
    }

    /** The text with these form values and what applying it would change. */
    @NotAudited
    @POST
    @Path("/check-form")
    public ConfigCheck checkConfigForm(@Valid ConfigForm form) {
        return config.checkForm(form);
    }

    /** Back to the state of a new installation, for the chosen parts. mosquitto.conf and retained messages stay. */
    @POST
    @Path("/reset")
    public ResetOutcome resetConfig(ResetRequest request) {
        return reset.reset(request);
    }

    @PUT
    public ConfigApplied applyConfig(@Valid ConfigText text) {
        return config.apply(text.text());
    }
}
