package com.dfc.mosquittoui.notify;

import com.dfc.mosquittoui.backup.BackupException;
import com.dfc.mosquittoui.backup.BackupTargetService;
import com.dfc.mosquittoui.notify.NotificationStore.MailSecurity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** The mail server, the recipients and what they want to hear about. */
@ApplicationScoped
public class NotificationSettingsService {

    public record MailSettings(@Schema(nullable = true) String host, int port, MailSecurity security, @Schema(nullable = true) String username,
            boolean passwordSet, @Schema(nullable = true) String from) {
    }

    public record NotificationSettings(List<String> recipients, MailSettings mail, List<Notice> muted, List<String> ignoredClients, long outageAfterMinutes,
            long clientAfterMinutes, int rejectedLogins, long rejectedWindowMinutes, long logMailEveryMinutes) {
    }

    /** mailPassword: null leaves it, empty removes it. */
    public record NotificationInput(@Schema(nullable = true) List<String> recipients, @Schema(nullable = true) String mailHost, @Schema(nullable = true) Integer mailPort,
            @Schema(nullable = true) MailSecurity mailSecurity, @Schema(nullable = true) String mailUsername, @Schema(nullable = true) String mailPassword,
            @Schema(nullable = true) String mailFrom, @Schema(nullable = true) List<Notice> muted, @Schema(nullable = true) List<String> ignoredClients) {
    }

    private static final int MAX_PORT = 65535;

    @Inject
    NotificationStore store;

    @ConfigProperty(name = "mosquitto-ui.notifications.outage-after")
    Duration outageAfter;

    @ConfigProperty(name = "mosquitto-ui.client-logins.missing-after")
    Duration clientAfter;

    @ConfigProperty(name = "mosquitto-ui.notifications.rejected-logins")
    int rejectedLogins;

    @ConfigProperty(name = "mosquitto-ui.notifications.rejected-window")
    Duration rejectedWindow;

    @ConfigProperty(name = "mosquitto-ui.notifications.log-mail-every")
    Duration logMailEvery;

    public NotificationSettings get() {
        return store.read(state -> {
            var s = state.settings;
            return new NotificationSettings(List.copyOf(s.recipients),
                    new MailSettings(s.mailHost, s.mailPort == null ? MailService.DEFAULT_PORT : s.mailPort, s.mailSecurity == null ? MailSecurity.STARTTLS : s.mailSecurity,
                            s.mailUsername, s.mailPassword != null, s.mailFrom),
                    List.copyOf(s.muted), List.copyOf(s.ignoredClients), outageAfter.toMinutes(), clientAfter.toMinutes(), rejectedLogins,
                    rejectedWindow.toMinutes(), logMailEvery.toMinutes());
        });
    }

    public NotificationSettings update(NotificationInput input) {
        List<String> recipients = input.recipients() == null ? List.of() : input.recipients().stream().map(String::strip).filter(r -> !r.isEmpty()).toList();
        if (recipients.stream().anyMatch(r -> !BackupTargetService.EMAIL.matcher(r).matches())) {
            throw BackupException.validation("recipients", "Invalid e-mail address");
        }
        if (input.mailPort() != null && (input.mailPort() < 1 || input.mailPort() > MAX_PORT)) {
            throw BackupException.validation("mailPort", "Invalid port");
        }
        String from = BackupTargetService.blank(input.mailFrom());
        if (from != null && !BackupTargetService.EMAIL.matcher(from).matches()) {
            throw BackupException.validation("mailFrom", "Invalid e-mail address");
        }
        store.change(state -> {
            var s = state.settings;
            s.recipients = new ArrayList<>(recipients);
            s.mailHost = BackupTargetService.blank(input.mailHost());
            s.mailPort = input.mailPort();
            s.mailSecurity = input.mailSecurity();
            s.mailUsername = BackupTargetService.blank(input.mailUsername());
            if (input.mailPassword() != null) {
                s.mailPassword = input.mailPassword().isEmpty() ? null : input.mailPassword();
            }
            s.mailFrom = from;
            s.muted = input.muted() == null ? new ArrayList<>() : new ArrayList<>(input.muted());
            s.ignoredClients = input.ignoredClients() == null ? new ArrayList<>() : new ArrayList<>(input.ignoredClients());
        });
        return get();
    }
}
