package com.dfc.mosquittoui.notify;

import com.dfc.mosquittoui.notify.NotificationStore.MailSecurity;
import com.dfc.mosquittoui.notify.NotificationStore.Settings;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.mail.LoginOption;
import io.vertx.ext.mail.MailAttachment;
import io.vertx.ext.mail.MailClient;
import io.vertx.ext.mail.MailConfig;
import io.vertx.ext.mail.MailMessage;
import io.vertx.ext.mail.StartTLSOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/** Sends e-mails with the mail server of the page Notifications: backups to e-mail targets and notices of what went wrong. */
@ApplicationScoped
public class MailService {

    /** Password based login methods; XOAUTH2 would need an OAuth token instead of the password. */
    static final String AUTH_METHODS = "DIGEST-MD5 CRAM-SHA256 CRAM-SHA1 CRAM-MD5 PLAIN LOGIN";
    private static final Logger LOG = Logger.getLogger(MailService.class);
    static final int DEFAULT_PORT = 587;
    private static final int SEND_SECONDS = 60;
    private static final long MB = 1024L * 1024L;

    /** Mail server, for sending. */
    record MailServer(String host, int port, MailSecurity security, String username, String password, String from) {
    }

    @Inject
    NotificationStore store;

    @Inject
    Vertx vertx;

    private MailServer server() {
        return store.read(state -> {
            Settings s = state.settings;
            return s.mailHost == null || s.mailFrom == null ? null
                    : new MailServer(s.mailHost, s.mailPort == null ? DEFAULT_PORT : s.mailPort, s.mailSecurity == null ? MailSecurity.STARTTLS : s.mailSecurity,
                            s.mailUsername, s.mailPassword, s.mailFrom);
        });
    }

    private MailServer requiredServer() throws IOException {
        MailServer server = server();
        if (server == null) {
            throw new IOException("No mail server configured: server and sender are missing on the page Notifications");
        }
        return server;
    }

    /** Sender address; fails without a mail server. */
    public String check() throws IOException {
        return requiredServer().from();
    }

    /** One message to all recipients in BCC with the files attached; fails if they exceed maxMb. */
    public void send(List<String> recipients, String subject, String text, List<Path> attachments, int maxMb) throws IOException {
        long total = 0;
        for (Path file : attachments) {
            total += Files.size(file);
        }
        if (total > maxMb * MB) {
            throw new IOException("The backup (" + (total / MB) + " MB) exceeds the limit of " + maxMb + " MB per e-mail");
        }
        MailServer server = requiredServer();
        List<MailAttachment> parts = new ArrayList<>();
        for (Path file : attachments) {
            String name = file.getFileName().toString();
            parts.add(MailAttachment.create().setName(name).setContentType(name.endsWith(".zip") ? "application/zip" : "application/octet-stream")
                    .setData(Buffer.buffer(Files.readAllBytes(file))));
        }
        deliver(server, new MailMessage().setFrom(server.from()).setBcc(recipients).setSubject(subject).setText(text).setAttachment(parts));
    }

    /** What failed once: without recipients or mail server, or if nobody wants to hear of it, it only shows in the log. */
    public void notifyFailure(Notice notice, String subject, String text) {
        try {
            if (!store.read(state -> state.settings.muted.contains(notice))) {
                deliverNotice(subject, text);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warnf("Notification not sent: %s", e.getMessage());
        }
    }

    /**
     * A message to the recipients of notices.
     *
     * @return false if there is nobody to send it to, or nothing to send it with
     * @throws IOException if the mail server does not take it
     */
    public boolean deliverNotice(String subject, String text) throws IOException {
        List<String> recipients = store.read(state -> List.copyOf(state.settings.recipients));
        MailServer server = server();
        if (recipients.isEmpty() || server == null) {
            return false;
        }
        deliver(server, new MailMessage().setFrom(server.from()).setTo(recipients).setSubject(subject).setText(text));
        return true;
    }

    /** Test message to the notification recipients. */
    public void sendTest() throws IOException {
        List<String> recipients = store.read(state -> List.copyOf(state.settings.recipients));
        if (recipients.isEmpty()) {
            throw new IOException("No recipients for notifications");
        }
        MailServer server = requiredServer();
        deliver(server, new MailMessage().setFrom(server.from()).setTo(recipients).setSubject("mosquitto-ui: test message")
                .setText("The mail server of mosquitto-ui works."));
    }

    void deliver(MailServer server, MailMessage message) throws IOException {
        MailConfig config = new MailConfig().setHostname(server.host()).setPort(server.port()).setUsername(server.username()).setPassword(server.password())
                .setLogin(server.username() == null ? LoginOption.DISABLED : LoginOption.REQUIRED)
                // Without XOAUTH2: servers like Gmail offer it first, but with an (app) password it fails
                .setAuthMethods(AUTH_METHODS).setSsl(server.security() == MailSecurity.TLS)
                .setStarttls(server.security() == MailSecurity.STARTTLS ? StartTLSOptions.REQUIRED : StartTLSOptions.DISABLED);
        MailClient client = MailClient.create(vertx, config);
        try {
            client.sendMail(message).toCompletionStage().toCompletableFuture().get(SEND_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Sending the e-mail was interrupted", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("Sending the e-mail failed: " + cause.getMessage(), cause);
        } finally {
            client.close();
        }
    }
}
