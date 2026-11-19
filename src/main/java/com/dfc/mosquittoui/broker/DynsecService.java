package com.dfc.mosquittoui.broker;

import com.dfc.mosquittoui.api.Dtos.AccessDefaults;
import com.dfc.mosquittoui.api.Dtos.AccessView;
import com.dfc.mosquittoui.api.Dtos.Acl;
import com.dfc.mosquittoui.api.Dtos.ClientCreate;
import com.dfc.mosquittoui.api.Dtos.ClientUpdate;
import com.dfc.mosquittoui.api.Dtos.ClientView;
import com.dfc.mosquittoui.api.Dtos.GroupCreate;
import com.dfc.mosquittoui.api.Dtos.GroupUpdate;
import com.dfc.mosquittoui.api.Dtos.GroupView;
import com.dfc.mosquittoui.api.Dtos.RoleAclChanges;
import com.dfc.mosquittoui.api.Dtos.RoleCreate;
import com.dfc.mosquittoui.api.Dtos.RoleUpdate;
import com.dfc.mosquittoui.api.Dtos.RoleView;
import com.dfc.mosquittoui.config.BrokerAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Clients, groups and roles of the dynamic security plugin. What this application needs to reach the broker itself, its
 * client with the groups and roles of that client, is locked: without it, the UI could not undo the change.
 */
@ApplicationScoped
public class DynsecService {

    /** The priority the plugin gives a role or group that was given none (and leaves out of its answers). */
    private static final int NO_PRIORITY = -1;

    /** Client and role of the page Topics, while it is open. */
    public static final String MONITOR = "mosquitto-ui-monitor";

    @Inject
    DynsecClient dynsec;

    @Inject
    MosquittoConfig config;

    @Inject
    ObjectMapper mapper;

    @Inject
    ClientLogins logins;

    @Inject
    BrokerAgent agent;

    /** Others that follow clients and roles, such as the file of the token login */
    @Inject
    Event<DynsecChanged> changed;

    private void changed() {
        changed.fireAsync(new DynsecChanged());
    }

    // --- clients ---

    public List<ClientView> listClients() {
        JsonNode data = dynsec.call(list("listClients"));
        List<ClientView> clients = new ArrayList<>();
        data.path("clients").forEach(client -> clients.add(toClient(client)));
        return clients;
    }

    public ClientView getClient(String username) {
        return toClient(dynsec.call(dynsec.command("getClient").put("username", username)).path("client"));
    }

    public void createClient(ClientCreate client) {
        ObjectNode command = withTexts(dynsec.command("createClient").put("username", client.username()).put("password", client.password()),
                client.textname(), client.textdescription());
        command.set("roles", refs("rolename", withPriorities(client.roles(), client.rolePriorities(), Map.of())));
        command.set("groups", refs("groupname", withPriorities(client.groups(), null, Map.of())));
        dynsec.call(command);
        changed();
    }

    /**
     * The plugin disconnects the client on every modifyClient, whatever it changes. Saving the dialog unchanged sends
     * nothing, and only what changed goes to the broker.
     */
    public void updateClient(String username, ClientUpdate client) {
        requireNotOwnClient(username);
        JsonNode raw = dynsec.call(dynsec.command("getClient").put("username", username)).path("client");
        ClientView current = toClient(raw);
        String textname = changedText(client.textname(), current.textname());
        String textdescription = changedText(client.textdescription(), current.textdescription());
        // the plugin takes the whole list: priorities not asked for go along as they are, else they would be lost
        Map<String, Integer> wantedRoles = withPriorities(client.roles(), client.rolePriorities(), current.rolePriorities());
        Map<String, Integer> wantedGroups = withPriorities(client.groups(), null, priorities(raw.path("groups"), "groupname"));
        boolean roles = !wantedRoles.equals(current.rolePriorities());
        boolean groups = !sameNames(client.groups(), current.groups());
        if (textname == null && textdescription == null && !roles && !groups) {
            return;
        }
        ObjectNode command = withTexts(dynsec.command("modifyClient").put("username", username), textname, textdescription);
        if (roles) {
            command.set("roles", refs("rolename", wantedRoles));
        }
        if (groups) {
            command.set("groups", refs("groupname", wantedGroups));
        }
        dynsec.call(command);
    }

    public void setClientPassword(String username, String password) {
        requireNotOwnClient(username);
        dynsec.call(dynsec.command("setClientPassword").put("username", username).put("password", password));
    }

    public void setClientEnabled(String username, boolean enabled) {
        requireNotOwnClient(username);
        dynsec.call(dynsec.command(enabled ? "enableClient" : "disableClient").put("username", username));
    }

    public void deleteClient(String username) {
        requireNotOwnClient(username);
        dynsec.call(dynsec.command("deleteClient").put("username", username));
        changed();
    }

    /** The plugin has no command for this, but disconnects a client on any change, and this one changes nothing. */
    public void disconnectClient(String username) {
        requireNotOwnClient(username);
        dynsec.call(dynsec.command("modifyClient").put("username", username));
    }

    // --- groups ---

    public List<GroupView> listGroups() {
        Set<String> locked = lockedGroups();
        String anonymous = anonymousGroup();
        JsonNode data = dynsec.call(list("listGroups"));
        List<GroupView> groups = new ArrayList<>();
        data.path("groups").forEach(group -> groups.add(toGroup(group, locked, anonymous)));
        return groups;
    }

    public void createGroup(GroupCreate group) {
        ObjectNode command = withTexts(dynsec.command("createGroup").put("groupname", group.groupname()), group.textname(), group.textdescription());
        command.set("roles", refs("rolename", withPriorities(group.roles(), group.rolePriorities(), Map.of())));
        dynsec.call(command);
        if (group.clients() != null) {
            setGroupClients(group.groupname(), group.clients());
        }
    }

    public void updateGroup(String groupname, GroupUpdate group) {
        requireNotLocked(lockedGroups(), groupname);
        // the plugin disconnects every member on each modifyGroup, so only when name, description or roles changed;
        // members coming and going disconnect just themselves
        JsonNode current = dynsec.call(dynsec.command("getGroup").put("groupname", groupname)).path("group");
        String textname = changedText(group.textname(), text(current, "textname"));
        String textdescription = changedText(group.textdescription(), text(current, "textdescription"));
        Map<String, Integer> currentRoles = priorities(current.path("roles"), "rolename");
        Map<String, Integer> wantedRoles = withPriorities(group.roles(), group.rolePriorities(), currentRoles);
        boolean roles = !wantedRoles.equals(currentRoles);
        if (textname != null || textdescription != null || roles) {
            ObjectNode command = withTexts(dynsec.command("modifyGroup").put("groupname", groupname), textname, textdescription);
            if (roles) {
                command.set("roles", refs("rolename", wantedRoles));
            }
            dynsec.call(command);
        }
        if (group.clients() != null) {
            setGroupClients(groupname, group.clients());
        }
    }

    /** The plugin knows no list of members, only each of them coming and going. */
    private void setGroupClients(String groupname, List<String> wanted) {
        List<String> current = names(dynsec.call(dynsec.command("getGroup").put("groupname", groupname)).path("group").path("clients"), "username");
        List<String> added = wanted.stream().filter(username -> !current.contains(username)).toList();
        List<String> removed = current.stream().filter(username -> !wanted.contains(username)).toList();
        added.forEach(this::requireNotOwnClient);
        removed.forEach(this::requireNotOwnClient);
        added.forEach(username -> dynsec.call(dynsec.command("addGroupClient").put("groupname", groupname).put("username", username)));
        removed.forEach(username -> dynsec.call(dynsec.command("removeGroupClient").put("groupname", groupname).put("username", username)));
    }

    public void deleteGroup(String groupname) {
        requireNotLocked(lockedGroups(), groupname);
        dynsec.call(dynsec.command("deleteGroup").put("groupname", groupname));
    }

    // --- roles ---

    public List<RoleView> listRoles() {
        Set<String> locked = lockedRoles();
        JsonNode data = dynsec.call(list("listRoles"));
        List<RoleView> roles = new ArrayList<>();
        data.path("roles").forEach(role -> roles.add(toRole(role, locked)));
        return roles;
    }

    /** With its rules in the same command, so that a rule the broker refuses leaves no role behind. */
    public void createRole(RoleCreate role) {
        ObjectNode command = withTexts(dynsec.command("createRole").put("rolename", role.rolename()), role.textname(), role.textdescription())
                .put("allowwildcardsubs", role.allowwildcardsubs());
        if (role.acls() != null && !role.acls().isEmpty()) {
            putAcls(command, role.acls());
        }
        dynsec.call(command);
        changed();
    }

    public void updateRole(String rolename, RoleUpdate role) {
        requireNotLocked(lockedRoles(), rolename);
        dynsec.call(withTexts(dynsec.command("modifyRole").put("rolename", rolename), role.textname(), role.textdescription())
                .put("allowwildcardsubs", role.allowwildcardsubs()));
        changed();
    }

    public void deleteRole(String rolename) {
        requireNotLocked(lockedRoles(), rolename);
        dynsec.call(dynsec.command("deleteRole").put("rolename", rolename));
        changed();
    }

    public void addRoleAcl(String rolename, Acl acl) {
        requireNotLocked(lockedRoles(), rolename);
        dynsec.call(dynsec.command("addRoleACL")
                .put("rolename", rolename)
                .put("acltype", acl.acltype())
                .put("topic", acl.topic())
                .put("priority", acl.priority())
                .put("allow", acl.allow()));
        changed();
    }

    public void removeRoleAcl(String rolename, String acltype, String topic) {
        requireNotLocked(lockedRoles(), rolename);
        dynsec.call(dynsec.command("removeRoleACL").put("rolename", rolename).put("acltype", acltype).put("topic", topic));
        changed();
    }

    /**
     * All rules of the role at once, in one modifyRole: the broker takes all of them or none, and disconnects the holders of the role
     * at most once instead of for every rule.
     */
    public RoleAclChanges setRoleAcls(String rolename, List<Acl> acls) {
        requireNotLocked(lockedRoles(), rolename);
        ObjectNode command = putAcls(dynsec.command("modifyRole").put("rolename", rolename), acls);
        // what the role had, for the audit log: what changed tells more than the whole list
        List<Acl> before = toRole(dynsec.call(dynsec.command("getRole").put("rolename", rolename)).path("role"), Set.of()).acls();
        dynsec.call(command);
        changed();
        return RoleAclChanges.between(before, acls);
    }

    /** The broker knows a rule by its type and topic; it would keep one of two with the same and drop the other unseen. */
    private static ObjectNode putAcls(ObjectNode command, List<Acl> rules) {
        Set<String> seen = new HashSet<>();
        ArrayNode acls = command.putArray("acls");
        for (Acl acl : rules) {
            if (!seen.add(acl.acltype() + " " + acl.topic())) {
                throw new DynsecException("Rule " + acl.acltype() + " " + acl.topic() + " appears twice");
            }
            acls.addObject()
                    .put("acltype", acl.acltype())
                    .put("topic", acl.topic())
                    .put("priority", acl.priority())
                    .put("allow", acl.allow());
        }
        return command;
    }

    // --- what holds for everyone ---

    public AccessView access() {
        Set<String> allowed = new HashSet<>();
        dynsec.call(dynsec.command("getDefaultACLAccess")).path("acls").forEach(acl -> {
            if (acl.path("allow").asBoolean()) {
                allowed.add(acl.path("acltype").asText());
            }
        });
        return new AccessView(new AccessDefaults(allowed.contains("publishClientSend"), allowed.contains("publishClientReceive"), allowed.contains("subscribe"),
                allowed.contains("unsubscribe")), anonymousGroup(), agent.dynsecKick());
    }

    /** Takes effect at the next check, nobody gets disconnected. */
    public void setDefaultAccess(AccessDefaults defaults) {
        ObjectNode command = dynsec.command("setDefaultACLAccess");
        ArrayNode acls = command.putArray("acls");
        acls.addObject().put("acltype", "publishClientSend").put("allow", defaults.publishClientSend());
        acls.addObject().put("acltype", "publishClientReceive").put("allow", defaults.publishClientReceive());
        acls.addObject().put("acltype", "subscribe").put("allow", defaults.subscribe());
        acls.addObject().put("acltype", "unsubscribe").put("allow", defaults.unsubscribe());
        dynsec.call(command);
        changed();
    }

    /** Disconnects the clients without a login. A group with the rights of this application would hand them to everyone. */
    public void setAnonymousGroup(String groupname) {
        requireNotLocked(lockedGroups(), groupname);
        dynsec.call(dynsec.command("setAnonymousGroup").put("groupname", groupname));
    }

    public String anonymousGroup() {
        return dynsec.call(dynsec.command("getAnonymousGroup")).path("group").path("groupname").asText();
    }

    // --- helpers ---

    private ObjectNode list(String name) {
        return dynsec.command(name).put("verbose", true).put("count", -1).put("offset", 0);
    }

    /** Leaves out texts without a value, the plugin rejects null. */
    private static ObjectNode withTexts(ObjectNode command, String textname, String textdescription) {
        if (textname != null) {
            command.put("textname", textname);
        }
        if (textdescription != null) {
            command.put("textdescription", textdescription);
        }
        return command;
    }

    /** Names with their priorities, as the plugin takes them; the higher one is checked first. */
    private ArrayNode refs(String field, Map<String, Integer> priorities) {
        ArrayNode refs = mapper.createArrayNode();
        priorities.forEach((name, priority) -> refs.addObject().put(field, name).put("priority", priority));
        return refs;
    }

    /** The priority of each name: as asked, else the one it has, else the default of the plugin. */
    private static Map<String, Integer> withPriorities(List<String> names, Map<String, Integer> asked, Map<String, Integer> current) {
        Map<String, Integer> priorities = new LinkedHashMap<>();
        for (String name : names) {
            Integer priority = asked != null ? asked.get(name) : null;
            priorities.put(name, priority != null ? priority : current.getOrDefault(name, NO_PRIORITY));
        }
        return priorities;
    }

    /** The plugin leaves out a priority it has not been given. */
    private static Map<String, Integer> priorities(JsonNode refs, String field) {
        Map<String, Integer> priorities = new LinkedHashMap<>();
        refs.forEach(ref -> priorities.put(ref.path(field).asText(), ref.path("priority").asInt(NO_PRIORITY)));
        return priorities;
    }

    private ClientView toClient(JsonNode client) {
        String username = client.path("username").asText();
        List<String> addresses = new ArrayList<>();
        client.path("connections").forEach(connection -> addresses.add(connection.path("address").asText()));
        return new ClientView(username, text(client, "textname"), text(client, "textdescription"),
                client.path("disabled").asBoolean(), names(client.path("roles"), "rolename"), priorities(client.path("roles"), "rolename"),
                names(client.path("groups"), "groupname"), addresses, ownClient(username), logins.get(username));
    }

    private GroupView toGroup(JsonNode group, Set<String> locked, String anonymous) {
        String groupname = group.path("groupname").asText();
        return new GroupView(groupname, text(group, "textname"), text(group, "textdescription"),
                names(group.path("roles"), "rolename"), priorities(group.path("roles"), "rolename"), names(group.path("clients"), "username"),
                locked.contains(groupname), groupname.equals(anonymous));
    }

    private RoleView toRole(JsonNode role, Set<String> locked) {
        String rolename = role.path("rolename").asText();
        List<Acl> acls = new ArrayList<>();
        role.path("acls").forEach(acl -> acls.add(new Acl(acl.path("acltype").asText(), acl.path("topic").asText(),
                acl.path("priority").asInt(), acl.path("allow").asBoolean())));
        return new RoleView(rolename, text(role, "textname"), text(role, "textdescription"),
                role.path("allowwildcardsubs").asBoolean(true), acls, locked.contains(rolename));
    }

    /** The new text if it differs from the current one, else null (withTexts then leaves it out). */
    private static String changedText(String wanted, String current) {
        return wanted == null || wanted.equals(current) ? null : wanted;
    }

    private static boolean sameNames(List<String> a, List<String> b) {
        return new HashSet<>(a).equals(new HashSet<>(b));
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }

    private static List<String> names(JsonNode refs, String field) {
        List<String> names = new ArrayList<>();
        refs.forEach(ref -> names.add(ref.path(field).asText()));
        return names;
    }

    private boolean ownClient(String username) {
        return username.equals(config.username()) || username.equals(MONITOR);
    }

    private void requireNotOwnClient(String username) {
        if (ownClient(username)) {
            throw new LockedException("Client " + username + " is used by this application");
        }
    }

    private void requireNotLocked(Set<String> locked, String name) {
        if (locked.contains(name)) {
            throw new LockedException(name + " gives this application its access to the broker");
        }
    }

    private Set<String> lockedGroups() {
        return new HashSet<>(getClient(config.username()).groups());
    }

    private Set<String> lockedRoles() {
        ClientView own = getClient(config.username());
        Set<String> roles = new HashSet<>(own.roles());
        roles.add(MONITOR);
        for (String group : own.groups()) {
            JsonNode data = dynsec.call(dynsec.command("getGroup").put("groupname", group));
            roles.addAll(names(data.path("group").path("roles"), "rolename"));
        }
        return roles;
    }
}
