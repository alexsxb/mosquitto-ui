package com.dfc.mosquittoui.notify;

import io.vertx.core.Vertx;
import io.vertx.ext.mail.MailMessage;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** SMTP login against a server that, like Gmail, offers XOAUTH2 first: the password must go with PLAIN or LOGIN. */
class MailServiceTest {

    @Test
    void passwordLoginDoesNotUseXoauth2() throws Exception {
        Vertx vertx = Vertx.vertx();
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> auth = CompletableFuture.supplyAsync(() -> serve(socket));
            MailService mail = new MailService();
            mail.vertx = vertx;
            mail.deliver(new MailService.MailServer("127.0.0.1", socket.getLocalPort(),
                            NotificationStore.MailSecurity.NONE, "backup@example.com", "app-password", "backup@example.com"),
                    new MailMessage().setFrom("backup@example.com").setTo(List.of("ops@example.com")).setSubject("test").setText("test"));
            String command = auth.get(10, TimeUnit.SECONDS);
            assertTrue(command.startsWith("AUTH PLAIN") || command.startsWith("AUTH LOGIN"), command);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /** Minimal SMTP dialogue; returns the AUTH command of the client. */
    private static String serve(ServerSocket server) {
        try (Socket client = server.accept();
             BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))) {
            OutputStream out = client.getOutputStream();
            String auth = null;
            write(out, "220 fake ESMTP");
            String line;
            while ((line = in.readLine()) != null) {
                String command = line.toUpperCase();
                if (command.startsWith("EHLO")) {
                    write(out, "250-fake\r\n250-AUTH XOAUTH2 PLAIN LOGIN\r\n250 OK");
                } else if (command.startsWith("AUTH")) {
                    auth = line;
                    if (command.equals("AUTH LOGIN")) {
                        write(out, "334 VXNlcm5hbWU6");
                        in.readLine();
                        write(out, "334 UGFzc3dvcmQ6");
                        in.readLine();
                    } else if (command.equals("AUTH PLAIN")) {
                        write(out, "334 ");
                        in.readLine();
                    }
                    write(out, "235 2.7.0 Accepted");
                } else if (command.startsWith("DATA")) {
                    write(out, "354 go ahead");
                    while (!".".equals(in.readLine())) {
                        // message body
                    }
                    write(out, "250 OK queued");
                } else if (command.startsWith("QUIT")) {
                    write(out, "221 bye");
                    break;
                } else {
                    write(out, "250 OK");
                }
            }
            return auth == null ? "no AUTH" : auth;
        } catch (Exception e) {
            return "error: " + e;
        }
    }

    private static void write(OutputStream out, String reply) throws Exception {
        out.write((reply + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }
}
