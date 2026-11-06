package com.dfc.mosquittoui.backup;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.DateTimeException;
import java.time.ZoneId;

/** Time zone of the schedules and how long runs stay in the log. */
@ApplicationScoped
public class BackupSettingsService {

    public record BackupSettings(String timeZone, int runRetentionDays) {
    }

    private static final int MAX_DAYS = 3650;

    @Inject
    BackupStore store;

    public BackupSettings get() {
        return store.read(state -> new BackupSettings(state.settings.timeZone, state.settings.runRetentionDays));
    }

    public BackupSettings update(BackupSettings input) {
        try {
            ZoneId.of(input.timeZone());
        } catch (DateTimeException | NullPointerException e) {
            throw BackupException.validation("timeZone", "Unknown time zone");
        }
        if (input.runRetentionDays() < 0 || input.runRetentionDays() > MAX_DAYS) {
            throw BackupException.validation("runRetentionDays", "Between 0 and 3650 days");
        }
        store.change(state -> {
            state.settings.timeZone = input.timeZone();
            state.settings.runRetentionDays = input.runRetentionDays();
        });
        return get();
    }

    public ZoneId timeZone() {
        return store.read(state -> ZoneId.of(state.settings.timeZone));
    }
}
