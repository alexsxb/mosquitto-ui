package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.audit.Audited;
import com.dfc.mosquittoui.rules.MessageRulesService;
import com.dfc.mosquittoui.rules.MessageRulesService.MessageRules;
import com.dfc.mosquittoui.rules.MessageRulesService.RulesStatus;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;

/** Message rules: what messages must look like and how many a client may send, enforced by the broker (docker/mosquitto/inspect). */
@Audited
@Path("/api/rules")
public class MessageRulesResource {

    @Inject
    MessageRulesService rules;

    @GET
    public MessageRules getMessageRules() {
        return rules.get();
    }

    /** Replaces all rules; the broker takes them within a second. */
    @PUT
    public MessageRules updateMessageRules(MessageRules input) {
        return rules.update(input);
    }

    /** What each rule refused since the broker started. */
    @GET
    @Path("/status")
    public RulesStatus messageRulesStatus() {
        return rules.status();
    }
}
