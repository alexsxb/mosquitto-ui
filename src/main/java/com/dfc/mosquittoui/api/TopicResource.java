package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.api.Dtos.RetainedCount;
import com.dfc.mosquittoui.api.Dtos.TopicBatch;
import com.dfc.mosquittoui.api.Dtos.TopicBranch;
import com.dfc.mosquittoui.api.Dtos.TopicPublish;
import com.dfc.mosquittoui.audit.Audited;
import com.dfc.mosquittoui.audit.NotAudited;
import com.dfc.mosquittoui.topics.TopicMonitor;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestStreamElementType;

@Audited
@Path("/api/topics")
public class TopicResource {

    private static final Logger LOG = Logger.getLogger(TopicResource.class);

    @Inject
    TopicMonitor monitor;

    @Inject
    SecurityIdentity identity;

    /** All known topics in the first events, then what changes. */
    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<TopicBatch> streamTopics() {
        return monitor.stream();
    }

    /** Needs the page to be open, its connection does the work. MQTT 3.1.1 cannot tell that the broker refused a message. */
    @POST
    @Path("/publish")
    public void publishTopic(@Valid TopicPublish message) {
        LOG.infof("%s publishes to %s", identity.getPrincipal().getName(), message.topic());
        monitor.publish(message);
    }

    /** All topics with a retained message, as the broker knows. Needs the page to be open, like publishing. */
    @GET
    @Path("/retained")
    public List<String> retainedTopics() {
        return monitor.allRetainedTopics();
    }

    /** How many retained messages a topic and all below it have, as the broker knows. */
    @NotAudited
    @POST
    @Path("/count-retained")
    public RetainedCount countRetained(@Valid TopicBranch branch) {
        return new RetainedCount(monitor.retainedTopics(branch.topic()).size());
    }

    /** A topic and all below it. Bridges pass this on like any other message. */
    @POST
    @Path("/clear-retained")
    public RetainedCount clearRetained(@Valid TopicBranch branch) {
        int cleared = monitor.clearRetained(branch.topic());
        LOG.infof("%s deleted %d retained messages below %s", identity.getPrincipal().getName(), cleared, branch.topic());
        return new RetainedCount(cleared);
    }
}
