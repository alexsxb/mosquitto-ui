package com.dfc.mosquittoui.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
/** Request and response bodies of the API. Names may not contain "/", they are path segments of the API. */
public final class Dtos {

    private static final String NAME = "[^/]+";
    /** Values end up in lines of a configuration file. */
    private static final String LINE = "[^\\r\\n]*";
    /** Without wildcards, and $-topics belong to the broker. */
    private static final String PUBLISH_TOPIC = "[^$#+\\x00][^#+\\x00]*";
    private static final String ACL_TYPE = "publishClientSend|publishClientReceive|subscribeLiteral|subscribePattern|unsubscribeLiteral|unsubscribePattern";

    private Dtos() {
    }

    public record ErrorResponse(String code, String message, String field) {
    }

    public record SystemInfo(String name, String version) {
    }

    public record CurrentUser(String username, String name, String email, boolean authorized) {
    }

    public record BrokerStatus(String host, int port, boolean connected, String error) {
    }

    /** What the broker says under $SYS, each null as long as it does not tell. The rates are per second. */
    public record BrokerStatsView(String version, Long uptimeSeconds, Long connections, Long subscriptions, Long retained, Long heapBytes,
            Double messagesIn, Double messagesOut, Double bytesIn, Double bytesOut) {
    }

    /** A point in the history of the broker; heapBytes is null for a broker built without memory tracking. */
    public record StatsSample(Instant time, long connections, double messagesIn, double messagesOut, double bytesIn, double bytesOut, Long heapBytes) {
    }

    /** A change through this application. error: why it failed, null if it worked. */
    public record AuditEntry(Instant time, String user, String action, Map<String, String> details, String error) {
    }

    /** A client that is usually connected and is not. */
    public record MissingClient(String username, String textname, Instant lastSeen) {
    }

    /** The last connection seen of a client: when it came and when it was last there, from which address. */
    public record ClientLogin(Instant connectedAt, Instant lastSeen, String address) {
    }

    /** locked: this application needs it to reach the broker, it cannot be changed here. lastLogin: null if none was seen. */
    public record ClientView(String username, String textname, String textdescription, boolean disabled, List<String> roles,
            List<String> groups, List<String> connections, boolean locked, ClientLogin lastLogin) {
    }

    public record ClientCreate(@NotBlank @Pattern(regexp = NAME) String username, @NotBlank String password, String textname,
            String textdescription, @NotNull List<String> roles, @NotNull List<String> groups) {
    }

    public record ClientUpdate(String textname, String textdescription, @NotNull List<String> roles, @NotNull List<String> groups) {
    }

    public record PasswordChange(@NotBlank String password) {
    }

    public record EnabledChange(boolean enabled) {
    }

    /** anonymous: clients without a login get the roles of this group; the plugin does not delete it. */
    public record GroupView(String groupname, String textname, String textdescription, List<String> roles, List<String> clients,
            boolean locked, boolean anonymous) {
    }

    /** clients: the members; null leaves them as they are. */
    public record GroupCreate(@NotBlank @Pattern(regexp = NAME) String groupname, String textname, String textdescription,
            @NotNull List<String> roles, @Schema(nullable = true) List<String> clients) {
    }

    /** clients: the members; null leaves them as they are. */
    public record GroupUpdate(String textname, String textdescription, @NotNull List<String> roles, @Schema(nullable = true) List<String> clients) {
    }

    public record RoleView(String rolename, String textname, String textdescription, boolean allowwildcardsubs, List<Acl> acls,
            boolean locked) {
    }

    public record RoleCreate(@NotBlank @Pattern(regexp = NAME) String rolename, String textname, String textdescription,
            boolean allowwildcardsubs) {
    }

    public record RoleUpdate(String textname, String textdescription, boolean allowwildcardsubs) {
    }

    public record Acl(@NotNull @Pattern(regexp = ACL_TYPE) String acltype, @NotBlank String topic, int priority, boolean allow) {
    }

    /** What a client may do where no rule of its roles matches. */
    public record AccessDefaults(boolean publishClientSend, boolean publishClientReceive, boolean subscribe, boolean unsubscribe) {
    }

    /** anonymousGroup: empty as long as none was set. */
    public record AccessView(AccessDefaults defaults, String anonymousGroup) {
    }

    public record AnonymousGroup(@NotBlank String groupname) {
    }

    /** username: null for a client without a login. clientId: for rules with %c; a client bound to an id gets that one if this is empty. */
    public record AclTest(@Schema(nullable = true) String username, @Schema(nullable = true) String clientId, @NotBlank String topic) {
    }

    /** RULE: a rule of a role. WILDCARDS: a role does not allow wildcards in subscriptions. BROKER: the broker decides before the plugin. */
    public enum AclReason {
        RULE, WILDCARDS, DEFAULT, CONTROL, BROKER, INVALID
    }

    /**
     * allowed: null if this topic cannot be used this way at all. role, rule: what decided, if a role did. group: the group that role came through,
     * null if the client has it itself.
     */
    public record AclDecision(String access, @Schema(nullable = true) Boolean allowed, AclReason reason, @Schema(nullable = true) String group,
            @Schema(nullable = true) String role, @Schema(nullable = true) Acl rule) {
    }

    /** disabled: the broker refuses this client anyway. */
    public record AclTestResult(boolean disabled, List<AclDecision> decisions) {
    }

    /**
     * proxyProtocol: version 1 or 2 of the PROXY protocol, which a proxy in front (Traefik, HAProxy) puts before each connection
     * with the address of the client; null without. Such a listener takes only connections through the proxy, and with version 2
     * no TLS of its own: the proxy ends it. proxyRequireTls: with version 2, only clients that used TLS up to the proxy.
     */
    public record ListenerForm(@Min(1) @Max(65535) int port, @Pattern(regexp = "\\S*") String bindAddress, @NotNull @Pattern(regexp = "mqtt|websockets") String protocol,
            @Pattern(regexp = LINE) String certfile, @Pattern(regexp = LINE) String keyfile, @Pattern(regexp = LINE) String cafile, @Min(-1) Integer maxConnections,
            @Min(1) @Max(2) Integer proxyProtocol, Boolean proxyRequireTls) {

        public ListenerForm(int port, String bindAddress, String protocol, String certfile, String keyfile, String cafile, Integer maxConnections) {
            this(port, bindAddress, protocol, certfile, keyfile, cafile, maxConnections, null, null);
        }
    }

    /** The common settings of the managed configuration file. options: by their name in mosquitto.conf, what is missing is left to the broker. */
    public record ConfigForm(@NotNull List<@Pattern(regexp = "[a-z]+") String> logTypes, @NotNull Map<String, @Pattern(regexp = LINE) String> options, @NotNull List<@Valid ListenerForm> listeners) {
    }

    /** available: the agent in the broker container answers, without it the configuration cannot be changed. mainPorts, mainListeners: those in mosquitto.conf. */
    public record ConfigView(boolean available, String text, ConfigForm form, java.util.Set<Integer> mainPorts, List<ListenerForm> mainListeners) {
    }

    public record ConfigText(@NotNull String text) {
    }

    /** restartRequired: the change only takes effect after a restart, which disconnects all clients. */
    public record ConfigCheck(String text, boolean restartRequired, List<String> changedOptions) {
    }

    public record ConfigApplied(boolean restarted) {
    }

    /** direction: as seen from this broker. The prefixes are put in front of the topic on either side. */
    public record BridgeTopic(@NotBlank @Pattern(regexp = "\\S+") String pattern, @NotNull @Pattern(regexp = "in|out|both") String direction,
            @Min(0) @Max(2) int qos, @Pattern(regexp = "\\S*") String localPrefix, @Pattern(regexp = "\\S*") String remotePrefix) {
    }

    /**
     * password: only sent to change it, it is never returned. Null: the default of the broker. extraOptions: lines the form does not
     * know. disabled: kept, but the broker does not see it; only changed through its own request.
     */
    public record BridgeForm(@NotBlank @Pattern(regexp = "[^/\\s]+") String name, @NotBlank @Pattern(regexp = LINE) String address,
            @Pattern(regexp = "\\S*") String remoteClientId, @Pattern(regexp = LINE) String username, @Pattern(regexp = LINE) String password,
            Boolean tryPrivate, Boolean cleanSession, @Pattern(regexp = "automatic|lazy|once") String startType, @Min(0) Integer keepalive,
            @Pattern(regexp = LINE) String restartTimeout, @Pattern(regexp = "mqttv31|mqttv311|mqttv50") String protocolVersion,
            @NotEmpty List<@Valid BridgeTopic> topics, @NotNull List<@Pattern(regexp = LINE) String> extraOptions, boolean disabled) {
    }

    /**
     * connected: null if the broker does not tell or the bridge is disabled. warnings: overlap, echo. victronPortalId: of the GX device at the other end.
     * victronCertificate: fingerprint (SHA-256) of the certificate of the device that the bridge trusts since pairing. victronCertificateChanged: the
     * fingerprint of another one the device presents now, null as long as nothing changed.
     */
    public record BridgeView(BridgeForm bridge, boolean passwordSet, Boolean connected, Instant stateSince, List<String> warnings, String victronPortalId,
            @Schema(nullable = true) String victronCertificate, @Schema(nullable = true) String victronCertificateChanged) {
    }

    /** A bridge to a Victron GX device. secured: through pairing, with TLS. twoWay: writes reach the device, otherwise it can only be read. */
    public record VictronPairing(@NotBlank @Pattern(regexp = "[^/\\s]+") String name, @NotBlank @Pattern(regexp = "[^\\s/:]+") String host, boolean secured, boolean twoWay,
            @Pattern(regexp = "\\S*") String localPrefix, @Pattern(regexp = "[0-9a-zA-Z]*") String portalId, @Min(1) @Max(65535) Integer port) {
    }

    public record BridgesView(boolean available, List<BridgeView> bridges) {
    }

    public record ImportFile(@NotBlank String name, @NotNull byte[] content) {
    }

    public record ImportFiles(@NotEmpty List<@Valid ImportFile> files) {
    }

    /** What to do with the files of the analysis; a part without files is left out in any case. */
    public record ImportRun(@NotEmpty List<@Valid ImportFile> files, boolean auth, boolean retained, boolean conf) {
    }

    /** kind: PASSWORD, ACL, DB, CONF or UNKNOWN; error: why it is not used. Files of a ZIP file are listed by their own name. */
    public record ImportFileInfo(String name, String kind, String error) {
    }

    /** status: NEW, EXISTS (left unchanged), INVALID (incomplete) or LOOP (messages would circle endlessly). */
    public record ConfImportBridge(String name, String address, int topics, String status, List<String> warnings) {
    }

    /** reason: auth, persistence, plugin, system, listener_in_use, listener_exists, already_set or orphan. */
    public record ConfSkipped(String line, String reason) {
    }

    /** options: the lines that go into the managed file. */
    public record ConfImportPlan(List<ConfImportBridge> bridges, List<String> options, List<ConfSkipped> skipped, boolean restartRequired) {
    }

    public record ConfImportResult(int bridges, int options, boolean restarted) {
    }

    /** A part is null if none of the files belongs to it. */
    public record ImportAnalysis(List<ImportFileInfo> files, AuthImportPlan auth, RetainedPlan retained, ConfImportPlan conf) {
    }

    /** The parts in the order they ran. error: why the next one failed, the ones after it did not run. */
    public record ImportOutcome(AuthImportResult auth, RetainedResult retained, ConfImportResult conf, String error) {
    }

    /** code: unsupported_hash, not_hashed, invalid_line, no_password or anonymous; subject: the user or the line. */
    public record ImportWarning(String code, String subject) {
    }

    /** status: NEW, EXISTS (left unchanged) or UNSUPPORTED (hash the broker cannot read). */
    public record ImportClient(String username, String hashType, String status, int rules) {
    }

    public record AuthImportPlan(List<ImportClient> clients, int patternRules, int anonymousRules, List<ImportWarning> warnings) {
    }

    public record AuthImportResult(int clients, int roles) {
    }

    public record RetainedMessage(String topic, int size, int qos, String preview) {
    }

    /** skipped: messages that cannot be published or have expired; withProperties: lose their MQTT 5 properties. */
    public record RetainedPlan(int version, List<RetainedMessage> messages, int skipped, int withProperties) {
    }

    public record RetainedResult(int published) {
    }

    /** value: text, or the start of the payload in hex if binary. cut: value is not all of it, size is. removed: the topic has no value anymore. */
    public record TopicUpdate(String topic, String value, boolean binary, int size, boolean cut, boolean retained, int qos, long count, boolean removed, long time) {
    }

    /** connected: the monitor reads the broker. error: why it does not, or what it leaves out. */
    public record TopicBatch(List<TopicUpdate> updates, int topics, boolean connected, String error) {
    }

    public record TopicPublish(@NotBlank @Pattern(regexp = PUBLISH_TOPIC) String topic, @NotNull String value, @Min(0) @Max(2) int qos, boolean retain) {
    }

    public record TopicBranch(@NotBlank @Pattern(regexp = PUBLISH_TOPIC) String topic) {
    }

    public record RetainedCount(int count) {
    }

    /** Each part on its own. access: clients, groups and roles, except what this application needs and the defaults of the plugin. */
    public record ResetRequest(boolean config, boolean bridges, boolean certificate, boolean access, boolean backups) {
    }

    /** done: the parts in the order they ran. error: why the next one failed, the ones after it did not run. */
    public record ResetOutcome(List<String> done, String error) {
    }

    /** token, eabHmacKey: only sent to change them, they are never returned. eabKeyId, eabHmacKey: of the account at a CA that needs one. */
    public record CertificateSettings(@NotNull @Pattern(regexp = "ACME_DNS|PDNS") String provider, @NotBlank String url, String token,
            String apiUser, String subdomain, @NotEmpty List<@NotBlank String> domains, String email, @NotBlank String directory,
            @NotNull @Pattern(regexp = "EC|RSA") String keyType, @Min(0) @Max(3600) int propagationSeconds, boolean autoRenew,
            boolean acceptTerms, boolean tlsListener, @Min(1) @Max(65535) Integer tlsPort, String eabKeyId, String eabHmacKey) {
    }

    public record CertificateInfo(String subject, List<String> domains, String issuer, Instant notBefore, Instant notAfter, long daysLeft,
            String serial, boolean renewalDue, Instant renewalFrom, String keyType) {
    }

    /** step: what a running job is doing, error: why the last one failed, setId: the set of the last order. */
    public record CertificateJob(boolean running, String step, String error, String setId) {
    }

    public record CertificateSetForm(@NotBlank @Size(max = 64) @Pattern(regexp = LINE) String name, @NotNull @Valid CertificateSettings settings) {
    }

    /** One set of settings and its certificate; at most one is active, that is the one the broker uses. */
    public record CertificateSetView(String id, String name, boolean active, CertificateSettings settings, boolean tokenSet, boolean eabKeySet,
            CertificateInfo certificate) {
    }

    /**
     * certificate: of the active set. certfile and keyfile: the paths for the listener, as the broker sees them, whichever set is
     * active. tlsPort: of the listener that uses them.
     */
    public record CertificateView(boolean available, List<CertificateSetView> sets, CertificateInfo certificate, CertificateJob job, String certfile,
            String keyfile, Integer tlsPort) {
    }
}
