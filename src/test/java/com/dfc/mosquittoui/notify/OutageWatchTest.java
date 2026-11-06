package com.dfc.mosquittoui.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dfc.mosquittoui.api.Dtos.BridgeForm;
import com.dfc.mosquittoui.api.Dtos.BridgeView;
import com.dfc.mosquittoui.api.Dtos.BridgesView;
import com.dfc.mosquittoui.api.Dtos.ClientView;
import com.dfc.mosquittoui.api.Dtos.MissingClient;
import com.dfc.mosquittoui.backup.BackupSettingsService;
import com.dfc.mosquittoui.broker.ClientLogins;
import com.dfc.mosquittoui.broker.DynsecClient;
import com.dfc.mosquittoui.broker.DynsecService;
import com.dfc.mosquittoui.config.BridgeService;
import com.dfc.mosquittoui.notify.NotificationStore.State;
import com.dfc.mosquittoui.notify.OutageWatch.Key;
import com.dfc.mosquittoui.notify.OutageWatch.Look;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** When a mail goes out, and when not. The way through a real mail server and a real bridge is in NotificationsTest. */
class OutageWatchTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final Duration AFTER = Duration.ofMinutes(5);
    private static final Key BROKER = new Key(Notice.BROKER, "broker");
    private static final Key CERBO = new Key(Notice.BRIDGE, "cerbo");
    private static final Key SHELLY = new Key(Notice.CLIENT, "shelly");
    private static final Key PLUG = new Key(Notice.CLIENT, "plug");

    private final State state = new State();
    private final List<String> mails = new ArrayList<>();
    private boolean serverDown;
    private boolean noServer;
    private OutageWatch watch;

    private final NotificationStore store = new NotificationStore() {
        @Override
        public synchronized <T> T read(Function<State, T> reader) {
            return reader.apply(state);
        }

        @Override
        public synchronized void change(Consumer<State> change) {
            change.accept(state);
        }
    };

    private final MailService mail = new MailService() {
        @Override
        public boolean deliverNotice(String subject, String text) throws IOException {
            if (serverDown) {
                throw new IOException("refused");
            }
            if (noServer) {
                return false;
            }
            mails.add(subject + "\n" + text);
            return true;
        }
    };

    private OutageWatch newWatch() {
        OutageWatch created = new OutageWatch();
        created.store = store;
        created.mail = mail;
        created.outageAfter = AFTER;
        created.settings = new BackupSettingsService() {
            @Override
            public ZoneId timeZone() {
                return ZoneId.of("UTC");
            }
        };
        return created;
    }

    @BeforeEach
    void start() {
        watch = newWatch();
    }

    private static Look down(Key... keys) {
        Map<Key, Instant> down = new HashMap<>();
        for (Key key : keys) {
            down.put(key, null);
        }
        return new Look(Set.of(), down, Set.of());
    }

    private static Look up(Key... keys) {
        return new Look(Set.of(keys), Map.of(), Set.of());
    }

    private static Instant later(int minutes) {
        return NOW.plus(Duration.ofMinutes(minutes));
    }

    private void reported(Key key) {
        watch.check(down(key), NOW);
        watch.check(down(key), later(5));
    }

    @Test
    void anOutageHasToLast() {
        watch.check(down(CERBO), NOW);
        watch.check(down(CERBO), later(4));
        watch.check(up(CERBO), later(4));
        watch.check(down(CERBO), later(5));
        watch.check(down(CERBO), later(9));
        assertEquals(List.of(), mails);

        watch.check(down(CERBO), later(10));
        assertEquals(1, mails.size());
        assertTrue(mails.getFirst().startsWith("mosquitto-ui: bridge cerbo is disconnected"), mails.getFirst());
        assertTrue(mails.getFirst().contains("since 2026-11-01 12:05 UTC"), mails.getFirst());
    }

    @Test
    void oneMailForAProblemAndOneForItsEnd() {
        reported(CERBO);
        watch.check(down(CERBO), later(6));
        watch.check(down(CERBO), later(600));
        assertEquals(1, mails.size());

        watch.check(up(CERBO), later(601));
        watch.check(up(CERBO), later(602));
        assertEquals(2, mails.size());
        assertTrue(mails.getLast().startsWith("mosquitto-ui: bridge cerbo is connected again"), mails.getLast());
    }

    @Test
    void anUpdateOfTheApplicationRepeatsNothingAndForgetsNothing() {
        reported(CERBO);
        watch = newWatch();
        watch.check(down(CERBO), later(6));
        watch.check(down(CERBO), later(60));
        assertEquals(1, mails.size());

        watch = newWatch();
        watch.check(up(CERBO), later(61));
        assertEquals(2, mails.size());
    }

    @Test
    void whatGoesAtOnceIsOneMail() {
        watch.check(down(BROKER, CERBO), NOW);
        watch.check(down(BROKER, CERBO), later(5));
        assertEquals(1, mails.size());
        assertTrue(mails.getFirst().startsWith("mosquitto-ui: 2 new problems"), mails.getFirst());
        assertTrue(mails.getFirst().contains("no connection to the broker"), mails.getFirst());
        assertTrue(mails.getFirst().contains("bridge cerbo is disconnected"), mails.getFirst());
    }

    @Test
    void theMailAboutAnEndNamesWhatIsStillOpen() {
        watch.check(down(BROKER, CERBO), NOW);
        watch.check(down(BROKER, CERBO), later(5));
        watch.check(new Look(Set.of(BROKER), Map.of(CERBO, later(1)), Set.of()), later(6));
        assertTrue(mails.getLast().contains("Solved:\n- the broker is back"), mails.getLast());
        assertTrue(mails.getLast().contains("Still open:\n- bridge cerbo is disconnected"), mails.getLast());
    }

    @Test
    void missingClientsHaveWaitedAlready() {
        watch.check(new Look(Set.of(), Map.of(SHELLY, NOW.minus(Duration.ofMinutes(16))), Set.of()), NOW);
        assertEquals(1, mails.size());
        assertTrue(mails.getFirst().contains("since 2026-11-01 11:44 UTC"), mails.getFirst());
    }

    @Test
    void aMailThatDidNotGoThroughIsTriedAgain() {
        serverDown = true;
        reported(CERBO);
        watch.check(down(CERBO), later(6));
        assertEquals(List.of(), state.reported);

        serverDown = false;
        watch.check(down(CERBO), later(7));
        assertEquals(1, mails.size());

        serverDown = true;
        watch.check(up(CERBO), later(8));
        assertEquals(1, state.reported.size());
        serverDown = false;
        watch.check(up(CERBO), later(9));
        assertEquals(2, mails.size());
        assertEquals(List.of(), state.reported);
    }

    @Test
    void withoutRecipientsItWaitsForSomeone() {
        noServer = true;
        reported(CERBO);
        assertEquals(List.of(), state.reported);

        noServer = false;
        watch.check(down(CERBO), later(6));
        assertEquals(1, mails.size());
    }

    @Test
    void whatIsTurnedOffStaysQuiet() {
        state.settings.muted.add(Notice.BRIDGE);
        reported(CERBO);
        assertEquals(List.of(), mails);
    }

    @Test
    void turningItOffDuringAnOutageMeansNoMailAboutItsEnd() {
        reported(CERBO);
        state.settings.muted.add(Notice.BRIDGE);
        watch.check(down(CERBO), later(6));
        assertEquals(List.of(), state.reported);

        watch.check(up(CERBO), later(7));
        assertEquals(1, mails.size());
    }

    @Test
    void aBridgeThatIsDeletedOrTurnedOffIsNoEnd() {
        reported(CERBO);
        watch.check(up(), later(6));
        assertEquals(1, mails.size());
        assertEquals(List.of(), state.reported);
    }

    @Test
    void whatCannotBeSeenStaysAsItIs() {
        watch.check(new Look(Set.of(), Map.of(SHELLY, NOW), Set.of()), NOW);
        Look brokerGone = new Look(Set.of(), Map.of(BROKER, NOW), Set.of(Notice.BRIDGE, Notice.CLIENT));
        watch.check(brokerGone, later(1));
        assertEquals(1, state.reported.size());
        assertEquals(1, mails.size());
    }

    // --- what a look sees ---

    private boolean connected = true;
    private final List<String> calls = new ArrayList<>();

    private OutageWatch observing(List<BridgeView> bridges, List<ClientView> clients, List<MissingClient> missing) {
        OutageWatch created = newWatch();
        created.dynsec = new DynsecClient() {
            @Override
            public boolean isConnected() {
                return connected;
            }
        };
        created.bridges = new BridgeService() {
            @Override
            public BridgesView view() {
                calls.add("bridges");
                return new BridgesView(true, bridges);
            }
        };
        created.clients = new DynsecService() {
            @Override
            public List<ClientView> listClients() {
                calls.add("clients");
                return clients;
            }
        };
        created.logins = new ClientLogins() {
            @Override
            public List<MissingClient> missing(List<ClientView> all, Instant now) {
                return missing;
            }
        };
        return created;
    }

    private static BridgeView bridge(String name, Boolean connected) {
        BridgeForm form = new BridgeForm(name, "gx:8883", null, null, null, null, null, null, null, null, null, List.of(), List.of(), false);
        return new BridgeView(form, false, connected, null, List.of(), null, null, null);
    }

    private static ClientView client(String name, boolean online) {
        return new ClientView(name, "", "", false, List.of(), List.of(), online ? List.of("10.0.0.1") : List.of(), false, null);
    }

    @Test
    void aBrokerThatIsGoneSaysNothingAboutTheRest() {
        connected = false;
        Look look = observing(List.of(), List.of(), List.of()).observe(NOW);
        assertEquals(Set.of(BROKER), look.down().keySet());
        assertEquals(Set.of(Notice.BRIDGE, Notice.CLIENT), look.unknown());
        assertEquals(List.of(), calls);
    }

    @Test
    void clientsGetTimeToComeBackAfterAnOutage() {
        OutageWatch observing = observing(List.of(), List.of(client("shelly", false)), List.of(new MissingClient("shelly", "", NOW.minus(Duration.ofHours(1)))));
        Look first = observing.observe(NOW);
        assertEquals(Set.of(Notice.CLIENT), first.unknown());
        assertEquals(Set.of(), first.down().keySet());

        connected = false;
        observing.observe(later(1));
        connected = true;
        assertEquals(Set.of(Notice.CLIENT), observing.observe(later(2)).unknown());
        assertEquals(Set.of(), observing.observe(later(6)).down().keySet());
        assertEquals(Set.of(SHELLY), observing.observe(later(7)).down().keySet());
    }

    @Test
    void aBridgeWithoutAStateIsNotDown() {
        Look look = observing(List.of(bridge("cerbo", false), bridge("loop", null), bridge("east", true)), List.of(), List.of()).observe(NOW);
        assertEquals(Set.of(CERBO), look.down().keySet());
        assertEquals(Set.of(BROKER, new Key(Notice.BRIDGE, "east")), look.up());
    }

    @Test
    void ignoredClientsAreNeitherGoneNorBack() {
        state.settings.ignoredClients.add("shelly");
        OutageWatch observing = observing(List.of(), List.of(client("shelly", true), client("plug", true)), List.of(new MissingClient("shelly", "", NOW)));
        observing.observe(NOW);
        Look look = observing.observe(later(5));
        assertEquals(Set.of(), look.down().keySet());
        assertEquals(Set.of(BROKER, PLUG), look.up());
    }
}
