package com.dfc.mosquittoui.notify;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A mail server that takes everything and keeps the messages. */
public class FakeSmtp implements AutoCloseable {

    public final List<String> messages = new CopyOnWriteArrayList<>();
    private final ServerSocket socket;

    public FakeSmtp() throws IOException {
        socket = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(this::run, "fake-smtp");
        thread.setDaemon(true);
        thread.start();
    }

    public int port() {
        return socket.getLocalPort();
    }

    private void run() {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                serve(client);
            } catch (IOException e) {
                // closed, or a client that left
            }
        }
    }

    private void serve(Socket client) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = client.getOutputStream();
        write(out, "220 fake ESMTP");
        String line;
        while ((line = in.readLine()) != null) {
            String command = line.toUpperCase();
            if (command.startsWith("DATA")) {
                write(out, "354 go ahead");
                StringBuilder message = new StringBuilder();
                while (!".".equals(line = in.readLine())) {
                    message.append(line).append('\n');
                }
                messages.add(message.toString());
                write(out, "250 OK");
            } else if (command.startsWith("QUIT")) {
                write(out, "221 bye");
                return;
            } else {
                write(out, "250 OK");
            }
        }
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write((text + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
