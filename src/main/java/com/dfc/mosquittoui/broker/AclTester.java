package com.dfc.mosquittoui.broker;

import com.dfc.mosquittoui.api.Dtos.AccessDefaults;
import com.dfc.mosquittoui.api.Dtos.Acl;
import com.dfc.mosquittoui.api.Dtos.AclDecision;
import com.dfc.mosquittoui.api.Dtos.AclReason;
import com.dfc.mosquittoui.api.Dtos.AclTest;
import com.dfc.mosquittoui.api.Dtos.AclTestResult;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out what the broker would decide, without touching it: the plugin has no command for this. Follows acl.c of the plugin and
 * acl_check.c of the broker in 2.1.2; AccessRulesTest compares the outcome with real clients, it has to be looked at with a new broker.
 * The plugin hands out roles and rules already sorted by priority, so the order of its answers is the order of the checks.
 */
@ApplicationScoped
public class AclTester {

    private static final String PUBLISH = "publishClientSend";
    private static final String RECEIVE = "publishClientReceive";
    private static final String SUBSCRIBE = "subscribe";
    private static final String UNSUBSCRIBE = "unsubscribe";
    private static final String BRIDGE_STATE = "$SYS/broker/connection/+/state";

    /** A role of a client, with the group it came through. */
    record Source(String group, String role, boolean wildcards, List<Acl> acls) {
    }

    /** Who asks: null if the client does not have that name or id. */
    record Identity(String username, String clientId) {
    }

    @Inject
    DynsecClient dynsec;

    @Inject
    DynsecService service;

    public AclTestResult test(AclTest test) {
        boolean anonymous = test.username() == null || test.username().isEmpty();
        JsonNode client = anonymous ? null : dynsec.call(dynsec.command("getClient").put("username", test.username())).path("client");
        Map<String, JsonNode> roles = new HashMap<>();
        dynsec.call(dynsec.command("listRoles").put("verbose", true).put("count", -1).put("offset", 0)).path("roles")
                .forEach(role -> roles.put(role.path("rolename").asText(), role));

        List<Source> sources = new ArrayList<>();
        if (anonymous) {
            addGroup(sources, service.anonymousGroup(), roles);
        } else {
            client.path("roles").forEach(role -> sources.add(source(null, role.path("rolename").asText(), roles)));
            client.path("groups").forEach(group -> addGroup(sources, group.path("groupname").asText(), roles));
        }

        String clientId = test.clientId() != null && !test.clientId().isEmpty() ? test.clientId() : anonymous ? null : client.path("clientid").asText(null);
        Identity identity = new Identity(anonymous ? null : test.username(), clientId);
        AccessDefaults defaults = service.access().defaults();
        return new AclTestResult(!anonymous && client.path("disabled").asBoolean(), List.of(
                decide(PUBLISH, test.topic(), identity, sources, defaults.publishClientSend()),
                decide(RECEIVE, test.topic(), identity, sources, defaults.publishClientReceive()),
                decide(SUBSCRIBE, test.topic(), identity, sources, defaults.subscribe()),
                decide(UNSUBSCRIBE, test.topic(), identity, sources, defaults.unsubscribe())));
    }

    private void addGroup(List<Source> sources, String groupname, Map<String, JsonNode> roles) {
        if (!groupname.isEmpty()) {
            dynsec.call(dynsec.command("getGroup").put("groupname", groupname)).path("group").path("roles")
                    .forEach(role -> sources.add(source(groupname, role.path("rolename").asText(), roles)));
        }
    }

    private static Source source(String group, String rolename, Map<String, JsonNode> roles) {
        JsonNode role = roles.get(rolename);
        List<Acl> acls = new ArrayList<>();
        role.path("acls").forEach(acl -> acls.add(new Acl(acl.path("acltype").asText(), acl.path("topic").asText(), acl.path("priority").asInt(), acl.path("allow").asBoolean())));
        return new Source(group, rolename, role.path("allowwildcardsubs").asBoolean(true), acls);
    }

    static AclDecision decide(String access, String topic, Identity identity, List<Source> sources, boolean defaultAllowed) {
        boolean subscription = access.equals(SUBSCRIBE) || access.equals(UNSUBSCRIBE);
        if (subscription ? !validFilter(topic) : hasWildcard(topic)) {
            return new AclDecision(access, null, AclReason.INVALID, null, null, null);
        }
        if (refusedByBroker(access, topic)) {
            return new AclDecision(access, false, AclReason.BROKER, null, null, null);
        }
        for (Source source : sources) {
            if (access.equals(SUBSCRIBE) && !source.wildcards() && hasWildcard(topic)) {
                return new AclDecision(access, false, AclReason.WILDCARDS, source.group(), source.role(), null);
            }
            Acl rule = subscription ? subscriptionRule(access, topic, identity, source) : messageRule(access, topic, identity, source);
            if (rule != null) {
                return new AclDecision(access, rule.allow(), AclReason.RULE, source.group(), source.role(), rule);
            }
        }
        if (defaultAllowed && topic.startsWith("$CONTROL")) {
            return new AclDecision(access, false, AclReason.CONTROL, null, null, null);
        }
        return new AclDecision(access, defaultAllowed, AclReason.DEFAULT, null, null, null);
    }

    private static Acl messageRule(String access, String topic, Identity identity, Source source) {
        return source.acls().stream().filter(acl -> acl.acltype().equals(access) && matches(acl.topic(), topic, identity)).findFirst().orElse(null);
    }

    /** A literal rule comes first, whatever the priorities say. */
    private static Acl subscriptionRule(String access, String filter, Identity identity, Source source) {
        return source.acls().stream().filter(acl -> acl.acltype().equals(access + "Literal") && acl.topic().equals(filter)).findFirst()
                .orElseGet(() -> source.acls().stream().filter(acl -> acl.acltype().equals(access + "Pattern") && matches(acl.topic(), filter, identity)).findFirst().orElse(null));
    }

    private static boolean refusedByBroker(String access, String topic) {
        if (topic.startsWith("$SYS")) {
            return access.equals(PUBLISH) && !matches(BRIDGE_STATE, topic, new Identity(null, null));
        }
        return topic.startsWith("$share") && (access.equals(PUBLISH) || access.equals(RECEIVE));
    }

    private static boolean hasWildcard(String topic) {
        return topic.contains("+") || topic.contains("#");
    }

    static boolean validFilter(String filter) {
        String[] levels = filter.split("/", -1);
        for (int i = 0; i < levels.length; i++) {
            boolean wildcard = hasWildcard(levels[i]);
            if (wildcard && !levels[i].equals("+") && !(levels[i].equals("#") && i == levels.length - 1)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a rule covers a topic or a subscription: "+" is one level, "#" the rest with its parent, and neither reaches topics with a "$" in
     * front. %u and %c stand for a whole level. As a subscription may hold wildcards itself, "+" of a rule does not cover its "#".
     */
    static boolean matches(String rule, String topic, Identity identity) {
        if (rule.startsWith("$") != topic.startsWith("$")) {
            return false;
        }
        String[] ruleLevels = rule.split("/", -1);
        String[] levels = topic.split("/", -1);
        for (int i = 0; i < ruleLevels.length; i++) {
            String level = ruleLevels[i];
            if (level.equals("#")) {
                return true;
            }
            if (i >= levels.length) {
                return false;
            }
            boolean matched = switch (level) {
                case "+" -> !levels[i].equals("#");
                case "%u" -> levels[i].equals(identity.username());
                case "%c" -> levels[i].equals(identity.clientId());
                default -> level.equals(levels[i]);
            };
            if (!matched) {
                return false;
            }
        }
        return ruleLevels.length == levels.length;
    }
}
