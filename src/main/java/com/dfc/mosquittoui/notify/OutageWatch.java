package com.dfc.mosquittoui.notify;

import com.dfc.mosquittoui.api.Dtos.BridgeView;
import com.dfc.mosquittoui.api.Dtos.ClientView;
import com.dfc.mosquittoui.api.Dtos.MissingClient;
import com.dfc.mosquittoui.backup.BackupSettingsService;
import com.dfc.mosquittoui.broker.ClientLogins;
import com.dfc.mosquittoui.broker.DynsecClient;
import com.dfc.mosquittoui.broker.DynsecService;
import com.dfc.mosquittoui.config.BridgeService;
import com.dfc.mosquittoui.notify.NotificationStore.Reported;
import com.dfc.mosquittoui.tokenauth.TokenClientLogins;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Tells the recipients when the broker, a bridge or a client is gone, and when it is back. One look makes one mail, however many went
 * at once. A problem has to last: a restart of the broker is no outage. What was reported is kept in a file, so that an update of this
 * application neither repeats a mail nor loses the one about the end. A mail that does not get through is tried again at the next look.
 */
@ApplicationScoped
public class OutageWatch {

    private static final Logger LOG = Logger.getLogger(OutageWatch.class);
    private static final String BROKER = "broker";

    /** The state of something being watched. */
    record Key(Notice notice, String name) {
    }

    /**
     * What one look saw.
     *
     * @param up what is fine, for sure
     * @param down what is not, with the time it went if that is known: null values are allowed
     * @param unknown what could not be seen this time, so nothing can be said about it
     */
    record Look(Set<Key> up, Map<Key, Instant> down, Set<Notice> unknown) {
    }

    @Inject
    DynsecClient dynsec;

    @Inject
    DynsecService clients;

    @Inject
    ClientLogins logins;

    @Inject
    TokenClientLogins tokenLogins;

    @Inject
    BridgeService bridges;

    @Inject
    NotificationStore store;

    @Inject
    MailService mail;

    @Inject
    BackupSettingsService settings;

    @ConfigProperty(name = "mosquitto-ui.notifications.outage-after")
    Duration outageAfter;

    private final Map<Key, Instant> firstSeen = new HashMap<>();
    private Instant connectedSince;

    @Scheduled(every = "{mosquitto-ui.notifications.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void look() {
        Instant now = Instant.now();
        check(observe(now), now);
    }

    Look observe(Instant now) {
        Set<Key> up = new HashSet<>();
        Map<Key, Instant> down = new HashMap<>();
        Set<Notice> unknown = EnumSet.noneOf(Notice.class);
        Key broker = new Key(Notice.BROKER, BROKER);
        if (!dynsec.isConnected()) {
            connectedSince = null;
            down.put(broker, null);
            unknown.add(Notice.BRIDGE);
            unknown.add(Notice.CLIENT);
            return new Look(up, down, unknown);
        }
        connectedSince = connectedSince == null ? now : connectedSince;
        up.add(broker);
        observeBridges(up, down, unknown);
        // After an outage, clients need a moment too: until then all of them would look missing
        if (connectedSince.plus(outageAfter).isAfter(now)) {
            unknown.add(Notice.CLIENT);
        } else {
            observeClients(now, up, down, unknown);
        }
        return new Look(up, down, unknown);
    }

    private void observeBridges(Set<Key> up, Map<Key, Instant> down, Set<Notice> unknown) {
        try {
            for (BridgeView view : bridges.view().bridges()) {
                Key key = new Key(Notice.BRIDGE, view.bridge().name());
                if (Boolean.TRUE.equals(view.connected())) {
                    up.add(key);
                } else if (view.connected() != null) {
                    down.put(key, view.stateSince());
                }
            }
        } catch (RuntimeException e) {
            LOG.debugf("Bridges not read: %s", e.getMessage());
            unknown.add(Notice.BRIDGE);
        }
    }

    private void observeClients(Instant now, Set<Key> up, Map<Key, Instant> down, Set<Notice> unknown) {
        try {
            List<ClientView> all = clients.listClients();
            Set<String> ignored = store.read(state -> Set.copyOf(state.settings.ignoredClients));
            all.stream().filter(client -> !client.connections().isEmpty() && !client.locked() && !ignored.contains(client.username()))
                    .forEach(client -> up.add(new Key(Notice.CLIENT, client.username())));
            for (MissingClient client : logins.missing(all, now)) {
                if (!ignored.contains(client.username())) {
                    down.put(new Key(Notice.CLIENT, client.username()), client.lastSeen());
                }
            }
            // those with a token: dynamic security does not know them, their names never are those of its clients
            Set<String> withToken = tokenLogins.connectedNow();
            if (withToken != null) {
                withToken.stream().filter(name -> !ignored.contains(name)).forEach(name -> up.add(new Key(Notice.CLIENT, name)));
                for (MissingClient client : tokenLogins.missing(now)) {
                    if (!ignored.contains(client.username())) {
                        down.put(new Key(Notice.CLIENT, client.username()), client.lastSeen());
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.debugf("Clients not read: %s", e.getMessage());
            unknown.add(Notice.CLIENT);
        }
    }

    /** Package private for tests, which have their own idea of the time. */
    void check(Look look, Instant now) {
        firstSeen.keySet().retainAll(look.down().keySet());
        look.down().keySet().forEach(key -> firstSeen.putIfAbsent(key, now));

        Set<Notice> muted = store.read(state -> Set.copyOf(state.settings.muted));
        List<Reported> reported = store.read(state -> List.copyOf(state.reported));
        Set<Key> known = reported.stream().map(OutageWatch::key).collect(Collectors.toSet());

        List<Reported> fresh = new ArrayList<>();
        for (Map.Entry<Key, Instant> entry : look.down().entrySet()) {
            Key key = entry.getKey();
            // the list of missing clients has its own waiting time
            boolean lasted = key.notice() == Notice.CLIENT || !firstSeen.get(key).plus(outageAfter).isAfter(now);
            if (lasted && !known.contains(key) && !muted.contains(key.notice())) {
                fresh.add(new Reported(key.notice(), key.name(), entry.getValue() != null ? entry.getValue() : firstSeen.get(key)));
            }
        }
        // what is not there any more, was turned off or gets ignored is not an end anyone wants a mail about
        List<Reported> still = reported.stream().filter(r -> !muted.contains(r.notice()) && (look.unknown().contains(r.notice()) || look.down().containsKey(key(r)) || look.up().contains(key(r)))).toList();
        List<Reported> over = still.stream().filter(r -> look.up().contains(key(r))).toList();
        List<Reported> open = still.stream().filter(r -> !over.contains(r)).toList();

        boolean sent = !fresh.isEmpty() || !over.isEmpty() ? send(fresh, over, open) : false;
        List<Reported> next = new ArrayList<>(sent ? open : still);
        if (sent) {
            next.addAll(fresh);
        }
        if (!next.equals(reported)) {
            store.change(state -> state.reported = next);
        }
    }

    private boolean send(List<Reported> fresh, List<Reported> over, List<Reported> open) {
        try {
            return mail.deliverNotice(subject(fresh, over), text(fresh, over, open));
        } catch (IOException e) {
            LOG.warnf("Notification not sent: %s", e.getMessage());
            return false;
        }
    }

    private static String subject(List<Reported> fresh, List<Reported> over) {
        if (fresh.size() == 1 && over.isEmpty()) {
            return "mosquitto-ui: " + problem(fresh.getFirst());
        }
        if (fresh.isEmpty() && over.size() == 1) {
            return "mosquitto-ui: " + solved(over.getFirst());
        }
        if (over.isEmpty()) {
            return "mosquitto-ui: " + fresh.size() + " new problems";
        }
        return "mosquitto-ui: " + (fresh.isEmpty() ? "" : fresh.size() + " new, ") + over.size() + " solved";
    }

    private String text(List<Reported> fresh, List<Reported> over, List<Reported> open) {
        DateTimeFormatter time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(settings.timeZone());
        StringBuilder text = new StringBuilder();
        section(text, "New problems", fresh.stream().map(r -> problem(r) + " (since " + time.format(r.since()) + ")").toList());
        section(text, "Solved", over.stream().map(OutageWatch::solved).toList());
        section(text, "Still open", open.stream().map(r -> problem(r) + " (since " + time.format(r.since()) + ")").toList());
        return text.append("This message was sent automatically.").toString();
    }

    private static void section(StringBuilder text, String title, List<String> lines) {
        if (!lines.isEmpty()) {
            text.append(title).append(":\n");
            lines.forEach(line -> text.append("- ").append(line).append('\n'));
            text.append('\n');
        }
    }

    private static String problem(Reported reported) {
        return switch (reported.notice()) {
            case BROKER -> "no connection to the broker";
            case BRIDGE -> "bridge " + reported.name() + " is disconnected";
            default -> "client " + reported.name() + " is gone";
        };
    }

    private static String solved(Reported reported) {
        return switch (reported.notice()) {
            case BROKER -> "the broker is back";
            case BRIDGE -> "bridge " + reported.name() + " is connected again";
            default -> "client " + reported.name() + " is back";
        };
    }

    private static Key key(Reported reported) {
        return new Key(reported.notice(), reported.name());
    }
}
