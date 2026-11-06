package com.dfc.mosquittoui.importer;

import com.dfc.mosquittoui.api.Dtos.GroupView;
import com.dfc.mosquittoui.api.Dtos.ResetOutcome;
import com.dfc.mosquittoui.api.Dtos.ResetRequest;
import com.dfc.mosquittoui.api.Dtos.RoleView;
import com.dfc.mosquittoui.broker.BrokerUnavailableException;
import com.dfc.mosquittoui.broker.DynsecException;
import com.dfc.mosquittoui.broker.DynsecService;
import com.dfc.mosquittoui.broker.MosquittoConfig;
import com.dfc.mosquittoui.backup.BackupRunService;
import com.dfc.mosquittoui.backup.BackupStore;
import com.dfc.mosquittoui.certificate.CertificateException;
import com.dfc.mosquittoui.certificate.CertificateService;
import com.dfc.mosquittoui.config.AgentException;
import com.dfc.mosquittoui.config.BridgeException;
import com.dfc.mosquittoui.config.BridgeService;
import com.dfc.mosquittoui.config.ConfigService;
import com.dfc.mosquittoui.notify.NotificationStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.jboss.logging.Logger;

/** Back to the state of a new installation, part by part. Bridges go before what they could depend on. */
@ApplicationScoped
public class ResetService {

    private static final Logger LOG = Logger.getLogger(ResetService.class);
    /** Created by the plugin. */
    private static final Set<String> DEFAULT_ROLES = Set.of("broker-admin", "client", "dynsec-admin", "super-admin", "sys-notify", "sys-observe", "topic-observe");

    @Inject
    BridgeService bridges;

    @Inject
    ConfigService config;

    @Inject
    CertificateService certificate;

    @Inject
    DynsecService dynsecService;

    @Inject
    ImportService imports;

    @Inject
    MosquittoConfig broker;

    @Inject
    BackupStore backups;

    @Inject
    NotificationStore notifications;

    @Inject
    BackupRunService runs;

    public ResetOutcome reset(ResetRequest request) {
        List<String> done = new ArrayList<>();
        try {
            if (request.bridges()) {
                bridges.reset();
                done.add("bridges");
            }
            if (request.config()) {
                config.reset();
                done.add("config");
            }
            if (request.certificate()) {
                certificate.reset();
                done.add("certificate");
            }
            if (request.access()) {
                resetAccess();
                done.add("access");
            }
            if (request.backups()) {
                if (runs.anyRunning()) {
                    return new ResetOutcome(done, "A backup is running");
                }
                backups.clear();
                notifications.clear();
                done.add("backups");
            }
        } catch (AgentException | BridgeException | BrokerUnavailableException | CertificateException | DynsecException e) {
            LOG.errorf("Reset stopped after %s: %s", done, e.getMessage());
            return new ResetOutcome(done, e.getMessage());
        }
        LOG.warnf("Reset of %s", done);
        return new ResetOutcome(done, null);
    }

    private static void keep(ObjectNode parent, String field, String key, Predicate<String> kept) {
        JsonNode list = parent.path(field);
        if (list instanceof ArrayNode array) {
            for (int i = array.size() - 1; i >= 0; i--) {
                if (!kept.test(array.get(i).path(key).asText())) {
                    array.remove(i);
                }
            }
        }
    }

    /** In the file of the plugin, in one step: one by one, each command would count for the clients that are still connected. */
    private void resetAccess() {
        Set<String> roles = new HashSet<>(DEFAULT_ROLES);
        dynsecService.listRoles().stream().filter(RoleView::locked).map(RoleView::rolename).forEach(roles::add);
        Set<String> groups = new HashSet<>();
        dynsecService.listGroups().stream().filter(GroupView::locked).map(GroupView::groupname).forEach(groups::add);
        imports.editDynsecFile(file -> {
            groups.add(file.path("anonymousGroup").asText());
            keep(file, "clients", "username", broker.username()::equals);
            keep(file, "groups", "groupname", groups::contains);
            keep(file, "roles", "rolename", roles::contains);
            file.path("groups").forEach(group -> keep((ObjectNode) group, "roles", "rolename", roles::contains));
        });
    }
}
