package com.dfc.mosquittoui.inspect;

import com.dfc.mosquittoui.backup.BackupException;
import com.dfc.mosquittoui.broker.BrokerUnavailableException;
import com.dfc.mosquittoui.broker.DynsecClient;
import com.dfc.mosquittoui.broker.TraceBatch;
import com.dfc.mosquittoui.tokenauth.TokenAuthService;
import com.dfc.mosquittoui.tokenauth.TokenAuthService.TokenClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jboss.logging.Logger;

/**
 * The look into the broker of the inspect plugin (docker/mosquitto/inspect/inspect.c): the sessions with what each client
 * subscribed and sent, and recordings of what clients do. The events of a recording come from the plugin about every second
 * and are kept here, in memory, for the last few recordings: gone with a restart of the UI.
 */
@ApplicationScoped
public class InspectService {

    /** Whether the plugin can be asked, and if not, why: plugin (not loaded) or rights (no access to $CONTROL/inspect/#). */
    public record InspectStatus(boolean available, @Schema(nullable = true) String reason) {
    }

    public record SessionSubscription(String topic, int qos, boolean noLocal, boolean retainAsPublished, @Schema(nullable = true) Integer identifier) {
    }

    /**
     * A session of the broker. subscriptions: those the plugin saw being made, since the broker started; subscriptionCount: what
     * the broker counts. kind: dynsec, token (logged in with an access token) or ui (a connection of this application). The
     * times and counts are since the broker started.
     */
    public record Session(String clientid, @Schema(nullable = true) String username, @Schema(nullable = true) String address, boolean connected,
            String protocol, String transport, int keepalive, boolean cleanStart, int subscriptionCount, List<SessionSubscription> subscriptions,
            @Schema(nullable = true) Instant connectedAt, @Schema(nullable = true) Instant offlineAt, long messagesIn, long messagesOut, long bytesIn,
            long bytesOut, @Schema(nullable = true) Instant lastIn, @Schema(nullable = true) Instant lastOut, long dropped, String kind) {
    }

    /** What to record: a client id (with a trailing * all that begin with it), a topic filter, or both. */
    public record TraceInput(@Schema(nullable = true) String clientid, @Schema(nullable = true) String topic, int seconds, int maxEvents,
            int payloadBytes) {
    }

    /**
     * A recording. end: why it ended, null while it runs: stopped, time, limit (maxEvents), replaced (another one started), lost
     * (no end came from the broker, as after a restart of it).
     */
    public record Trace(String id, @Schema(nullable = true) String clientid, @Schema(nullable = true) String topic, Instant started, Instant until,
            int maxEvents, int payloadBytes, String startedBy, boolean active, @Schema(nullable = true) String end, int count) {
    }

    /**
     * One event of a recording. type: connect, offline (the session stays), disconnect (the session ends), subscribe,
     * subscribeDenied, unsubscribe, publish (from the client), publishDenied, dropped (refused by the message rule rule, why in
     * reasonText), deliver (to the client). payload: base64, cut at payloadBytes of the recording (truncated).
     */
    public record TraceEvent(Instant at, String type, String clientid, @Schema(nullable = true) String username, @Schema(nullable = true) String address,
            @Schema(nullable = true) String protocol, @Schema(nullable = true) Integer keepalive, @Schema(nullable = true) Boolean cleanStart,
            @Schema(nullable = true) String transport, @Schema(nullable = true) String topic, @Schema(nullable = true) Integer qos,
            @Schema(nullable = true) Boolean retain, @Schema(nullable = true) Integer size, @Schema(nullable = true) String payload, boolean truncated,
            @Schema(nullable = true) Integer reason, @Schema(nullable = true) String reasonText, @Schema(nullable = true) String rule) {
    }

    /** A recording with its events from after on, and how many it has in all. */
    public record TraceView(Trace trace, List<TraceEvent> events, int total) {
    }

    private static final Logger LOG = Logger.getLogger(InspectService.class);
    static final int MAX_SECONDS = 3600;
    static final int MAX_EVENTS = 50000;
    static final int MAX_PAYLOAD_BYTES = 4096;
    /** Recordings kept, the oldest go first */
    static final int KEPT = 5;
    /** After its end time, so long until a recording without end from the broker counts as lost */
    private static final Duration GRACE = Duration.ofSeconds(15);

    @Inject
    DynsecClient broker;

    @Inject
    TokenAuthService tokenAuth;

    private final Map<String, Trace> traces = new LinkedHashMap<>();
    private final Map<String, List<TraceEvent>> events = new LinkedHashMap<>();

    public InspectStatus status() {
        if (!broker.isConnected()) {
            return new InspectStatus(false, "broker");
        }
        if (broker.sysValue(DynsecClient.INSPECT_VERSION) == null) {
            return new InspectStatus(false, "plugin");
        }
        if (!broker.inspectReadable()) {
            return new InspectStatus(false, "rights");
        }
        return new InspectStatus(true, null);
    }

    private JsonNode call(ObjectNode command) {
        InspectStatus status = status();
        if (!status.available()) {
            throw new BrokerUnavailableException(switch (status.reason()) {
                case "plugin" -> "The inspect plugin is not loaded in the broker";
                case "rights" -> "The broker user of the UI may not use $CONTROL/inspect/#";
                default -> "Not connected to the broker";
            });
        }
        return broker.call(DynsecClient.INSPECT_TOPIC, command);
    }

    private static Instant seconds(JsonNode node) {
        return node.isNumber() ? Instant.ofEpochSecond(node.asLong()) : null;
    }

    private static String text(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    /** What the message rules refused, as the plugin counts it: { loaded, error, content: [...], rate: [...] }. */
    public JsonNode ruleStats() {
        return call(broker.command("ruleStats"));
    }

    /** All sessions of the broker, those connected first, then by client id. */
    public List<Session> sessions() {
        JsonNode data = call(broker.command("listClients"));
        List<TokenClient> tokenClients = tokenAuth.clients();
        Set<String> tokenIds = tokenClients.stream().map(TokenClient::clientid).collect(Collectors.toSet());
        List<Session> sessions = new ArrayList<>();
        for (JsonNode c : data.path("clients")) {
            List<SessionSubscription> subscriptions = new ArrayList<>();
            for (JsonNode s : c.path("subscriptions")) {
                subscriptions.add(new SessionSubscription(s.path("topic").asText(), s.path("qos").asInt(), s.path("noLocal").asBoolean(),
                        s.path("retainAsPublished").asBoolean(), s.has("identifier") ? s.get("identifier").asInt() : null));
            }
            subscriptions.sort(Comparator.comparing(SessionSubscription::topic));
            String clientid = c.path("clientid").asText();
            String kind = clientid.startsWith(DynsecClient.CLIENT_ID_PREFIX) ? "ui" : tokenIds.contains(clientid) ? "token" : "dynsec";
            sessions.add(new Session(clientid, text(c.path("username")), text(c.path("address")), c.path("connected").asBoolean(), c.path("protocol").asText(),
                    c.path("transport").asText(), c.path("keepalive").asInt(), c.path("cleanStart").asBoolean(), c.path("subscriptionCount").asInt(),
                    subscriptions, seconds(c.path("connectedAt")), seconds(c.path("offlineAt")), c.path("messagesIn").asLong(), c.path("messagesOut").asLong(),
                    c.path("bytesIn").asLong(), c.path("bytesOut").asLong(), seconds(c.path("lastIn")), seconds(c.path("lastOut")), c.path("dropped").asLong(),
                    kind));
        }
        sessions.sort(Comparator.comparing(Session::connected).reversed().thenComparing(Session::clientid));
        return sessions;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Starts a recording; one that runs ends, the broker records one at a time. */
    public Trace start(TraceInput input, String startedBy) {
        String clientid = blank(input.clientid());
        String topic = blank(input.topic());
        if (clientid == null && topic == null) {
            throw BackupException.validation("clientid", "A client id or a topic filter is needed");
        }
        if (clientid != null && clientid.indexOf('*') >= 0 && clientid.indexOf('*') != clientid.length() - 1) {
            throw BackupException.validation("clientid", "Only a * at the end is allowed");
        }
        if (input.seconds() < 1 || input.seconds() > MAX_SECONDS) {
            throw BackupException.validation("seconds", "Between 1 and " + MAX_SECONDS + " seconds");
        }
        if (input.maxEvents() < 1 || input.maxEvents() > MAX_EVENTS) {
            throw BackupException.validation("maxEvents", "Between 1 and " + MAX_EVENTS + " events");
        }
        if (input.payloadBytes() < 0 || input.payloadBytes() > MAX_PAYLOAD_BYTES) {
            throw BackupException.validation("payloadBytes", "Between 0 and " + MAX_PAYLOAD_BYTES + " bytes");
        }
        String id = UUID.randomUUID().toString();
        ObjectNode command = broker.command("startTrace").put("id", id).put("seconds", input.seconds()).put("maxEvents", input.maxEvents())
                .put("payloadBytes", input.payloadBytes()).put("startedBy", startedBy);
        if (clientid != null) {
            command.put("clientid", clientid);
        }
        if (topic != null) {
            command.put("topic", topic);
        }
        call(command);
        Instant now = Instant.now();
        Trace trace = new Trace(id, clientid, topic, now, now.plusSeconds(input.seconds()), input.maxEvents(), input.payloadBytes(), startedBy, true, null, 0);
        synchronized (this) {
            // the broker ended the one before, its last events may still come
            traces.replaceAll((key, old) -> old.active() ? ended(old, "replaced") : old);
            traces.put(id, trace);
            events.put(id, new ArrayList<>());
            while (traces.size() > KEPT) {
                String oldest = traces.keySet().iterator().next();
                traces.remove(oldest);
                events.remove(oldest);
            }
        }
        LOG.infof("Trace %s started by %s: client %s, topic %s, %d s", id, startedBy, clientid, topic, input.seconds());
        return trace;
    }

    private static Trace ended(Trace trace, String end) {
        return new Trace(trace.id(), trace.clientid(), trace.topic(), trace.started(), trace.until(), trace.maxEvents(), trace.payloadBytes(),
                trace.startedBy(), false, end, trace.count());
    }

    /** Stops a recording that runs; the events so far stay. */
    public Trace stop(String id) {
        Trace trace = trace(id);
        if (trace.active()) {
            call(broker.command("stopTrace").put("id", id));
            synchronized (this) {
                trace = traces.get(id);
                if (trace != null && trace.active()) {
                    trace = ended(trace, "stopped");
                    traces.put(id, trace);
                }
            }
        }
        return trace;
    }

    /** Forgets a recording that has ended. */
    public synchronized void delete(String id) {
        Trace trace = trace(id);
        if (trace.active()) {
            throw BackupException.validation("id", "The trace still runs");
        }
        traces.remove(id);
        events.remove(id);
    }

    private synchronized Trace trace(String id) {
        expire();
        Trace trace = traces.get(id);
        if (trace == null) {
            throw new jakarta.ws.rs.NotFoundException("No trace " + id);
        }
        return trace;
    }

    private void expire() {
        Instant now = Instant.now();
        traces.replaceAll((key, trace) -> trace.active() && trace.until().plus(GRACE).isBefore(now) ? ended(trace, "lost") : trace);
    }

    /** The recordings kept, the latest first. */
    public synchronized List<Trace> traces() {
        expire();
        List<Trace> all = new ArrayList<>(traces.values());
        java.util.Collections.reverse(all);
        return all;
    }

    /** A recording with its events from index after on. */
    public synchronized TraceView view(String id, int after) {
        Trace trace = trace(id);
        List<TraceEvent> all = events.getOrDefault(id, List.of());
        int from = Math.clamp(after, 0, all.size());
        return new TraceView(trace, List.copyOf(all.subList(from, all.size())), all.size());
    }

    void onBatch(@Observes TraceBatch message) {
        JsonNode batch = message.batch();
        String id = batch.path("id").asText();
        synchronized (this) {
            Trace trace = traces.get(id);
            List<TraceEvent> list = events.get(id);
            if (trace == null || list == null) {
                // started by another instance of the UI, or before a restart of this one
                return;
            }
            for (JsonNode e : batch.path("events")) {
                if (list.size() >= trace.maxEvents()) {
                    break;
                }
                list.add(new TraceEvent(Instant.ofEpochMilli(e.path("at").asLong()), e.path("type").asText(), e.path("clientid").asText(),
                        text(e.path("username")), text(e.path("address")), text(e.path("protocol")), e.has("keepalive") ? e.get("keepalive").asInt() : null,
                        e.has("cleanStart") ? e.get("cleanStart").asBoolean() : null, text(e.path("transport")), text(e.path("topic")),
                        e.has("qos") ? e.get("qos").asInt() : null, e.has("retain") ? e.get("retain").asBoolean() : null,
                        e.has("size") ? e.get("size").asInt() : null, text(e.path("payload")), e.path("truncated").asBoolean(),
                        e.has("reason") ? e.get("reason").asInt() : null, text(e.path("reasonText")), text(e.path("rule"))));
            }
            String end = text(batch.path("end"));
            boolean active = trace.active() && end == null;
            traces.put(id, new Trace(trace.id(), trace.clientid(), trace.topic(), trace.started(), trace.until(), trace.maxEvents(), trace.payloadBytes(),
                    trace.startedBy(), active, active ? null : trace.end() != null ? trace.end() : end, list.size()));
        }
    }
}
