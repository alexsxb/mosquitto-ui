package com.dfc.mosquittoui.localauth;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Against guessing passwords: after {@code max-failures} failed logins within {@code window}, for one user name or from one address,
 * further attempts are refused until the oldest of them is older than the window. In memory: a restart starts again.
 */
@ApplicationScoped
public class LoginThrottle {

    /** Keys kept at most, the oldest go first: someone trying many names or addresses cannot fill the memory */
    static final int MAX_KEYS = 10_000;

    @ConfigProperty(name = "mosquitto-ui.local-auth.max-failures", defaultValue = "5")
    int maxFailures;

    @ConfigProperty(name = "mosquitto-ui.local-auth.failure-window", defaultValue = "PT15M")
    Duration window;

    private final Map<String, Deque<Instant>> failures = new HashMap<>();

    /** @return null if an attempt is allowed, otherwise how long to wait */
    public synchronized Duration blocked(String username, String address, Instant now) {
        Duration wait = null;
        for (String key : keys(username, address)) {
            Deque<Instant> times = recent(key, now);
            if (times != null && times.size() >= maxFailures) {
                Duration left = Duration.between(now, times.peekFirst().plus(window));
                wait = wait == null || left.compareTo(wait) > 0 ? left : wait;
            }
        }
        return wait;
    }

    public synchronized void failed(String username, String address, Instant now) {
        for (String key : keys(username, address)) {
            failures.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(now);
            recent(key, now);
        }
        if (failures.size() > MAX_KEYS) {
            prune(now);
        }
    }

    /** The name is fine again; the address keeps its count, it may be trying several names */
    public synchronized void succeeded(String username) {
        failures.remove(userKey(username));
    }

    private Deque<Instant> recent(String key, Instant now) {
        Deque<Instant> times = failures.get(key);
        if (times == null) {
            return null;
        }
        while (!times.isEmpty() && !times.peekFirst().plus(window).isAfter(now)) {
            times.removeFirst();
        }
        while (times.size() > maxFailures) {
            times.removeFirst();
        }
        if (times.isEmpty()) {
            failures.remove(key);
            return null;
        }
        return times;
    }

    private void prune(Instant now) {
        for (Iterator<String> keys = failures.keySet().iterator(); keys.hasNext();) {
            Deque<Instant> times = failures.get(keys.next());
            if (times.isEmpty() || !times.peekLast().plus(window).isAfter(now)) {
                keys.remove();
            }
        }
        // still too many: the oldest first
        while (failures.size() > MAX_KEYS) {
            String oldest = failures.entrySet().stream().min(Map.Entry.comparingByValue((a, b) -> a.peekLast().compareTo(b.peekLast()))).get().getKey();
            failures.remove(oldest);
        }
    }

    private static String userKey(String username) {
        return "u:" + (username == null ? "" : username.toLowerCase(Locale.ROOT));
    }

    private static String[] keys(String username, String address) {
        return address == null ? new String[] {userKey(username)} : new String[] {userKey(username), "a:" + address};
    }
}
