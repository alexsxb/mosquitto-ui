package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import com.dfc.mosquittoui.api.Dtos.ConfigForm;
import com.dfc.mosquittoui.api.Dtos.ListenerForm;
import com.dfc.mosquittoui.config.ManagedConfig;
import io.restassured.http.ContentType;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A listener behind a TCP proxy such as Traefik: the address of the client comes with the PROXY protocol, and the page shows that one. */
@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@TestSecurity(user = "alice", roles = "mosquitto-admin")
class ProxyProtocolTest {

    /** PROXY protocol version 2, TCP over IPv4, as Traefik sends it before the connection of a client from 203.0.113.7: addresses only. */
    private static byte[] proxyHeader() {
        return proxyHeader(new byte[0]);
    }

    /** The TLS entry (PP2_TYPE_SSL) as HAProxy sends it with send-proxy-v2-ssl for a client that came with TLS 1.3. */
    private static byte[] tlsEntry(int client) {
        byte[] version = "TLSv1.3".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        value.write(client);
        value.writeBytes(new byte[] {0, 0, 0, 0}); // verify: 0, nothing failed
        value.write(0x21); // PP2_SUBTYPE_SSL_VERSION
        value.writeBytes(new byte[] {0x00, (byte) version.length});
        value.writeBytes(version);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x20); // PP2_TYPE_SSL
        out.writeBytes(new byte[] {0x00, (byte) value.size()});
        out.writeBytes(value.toByteArray());
        return out.toByteArray();
    }

    private static byte[] proxyHeader(byte[] tlvs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A});
        out.write(0x21); // version 2, PROXY
        out.write(0x11); // TCP over IPv4
        out.writeBytes(new byte[] {0x00, (byte) (0x0C + tlvs.length)});
        out.writeBytes(new byte[] {(byte) 203, 0, 113, 7});
        out.writeBytes(new byte[] {10, 0, 0, 2});
        out.writeBytes(new byte[] {(byte) 0xC3, 0x50});
        out.writeBytes(new byte[] {0x07, 0x5C});
        out.writeBytes(tlvs);
        return out.toByteArray();
    }

    private static void string(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(bytes.length >> 8);
        out.write(bytes.length & 0xFF);
        out.writeBytes(bytes);
    }

    /** CONNECT of MQTT 3.1.1 with user name and password. */
    private static byte[] connect(String clientId, String username, String password) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        string(body, "MQTT");
        body.write(4);
        body.write(0xC2);
        body.writeBytes(new byte[] {0x00, 0x3C});
        string(body, clientId);
        string(body, username);
        string(body, password);
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        packet.write(0x10);
        packet.write(body.size());
        packet.writeBytes(body.toByteArray());
        return packet.toByteArray();
    }

    private static String withListener() {
        return withListener(null);
    }

    private static String withListener(Boolean requireTls) {
        await().until(() -> given().get("/api/config").jsonPath().getBoolean("available"));
        String before = given().get("/api/config").jsonPath().getString("text");
        // as the page does it: through the form, in place of a listener on the port that another test may have left
        ConfigForm form = ManagedConfig.parse(before);
        List<ListenerForm> listeners = new ArrayList<>(form.listeners().stream().filter(listener -> listener.port() != 1884).toList());
        listeners.add(new ListenerForm(1884, null, "mqtt", null, null, null, null, 2, requireTls));
        String text = ManagedConfig.render(before, new ConfigForm(form.logTypes(), form.options(), listeners));
        given().contentType(ContentType.JSON).body(Map.of("text", text)).put("/api/config").then().log().ifValidationFails().statusCode(200);
        return before;
    }

    private static Socket open() throws IOException {
        Socket socket = new Socket(MosquittoResource.broker.getHost(), MosquittoResource.broker.getMappedPort(1884));
        socket.setSoTimeout(5000);
        return socket;
    }

    @Test
    void theAddressOfTheClientComesFromTheProxy() throws IOException {
        String before = withListener();
        given().get("/api/config").then().body("form.listeners.find { it.port == 1884 }.proxyProtocol", equalTo(2));
        given().contentType(ContentType.JSON).body(Map.of("username", "behind-proxy", "password", "behind-proxy-password", "roles", List.of(), "groups", List.of()))
                .post("/api/clients").then().statusCode(204);
        try (Socket socket = open()) {
            socket.getOutputStream().write(proxyHeader());
            socket.getOutputStream().write(connect("behind-proxy-1", "behind-proxy", "behind-proxy-password"));
            byte[] connack = new byte[4];
            new DataInputStream(socket.getInputStream()).readFully(connack);
            assertArrayEquals(new byte[] {0x20, 0x02, 0x00, 0x00}, connack, "accepted");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given().get("/api/clients/behind-proxy").then().body("connections[0]", containsString("203.0.113.7")));
        } finally {
            given().delete("/api/clients/behind-proxy").then().statusCode(204);
            given().contentType(ContentType.JSON).body(Map.of("text", before)).put("/api/config").then().statusCode(200);
        }
    }

    /**
     * What the broker sends back, up to a CONNACK. It ends a connection without the header instead of answering: closed, or
     * reset, as seen through the port mapping of Docker on the CI runner. Both mean nothing gets in.
     */
    private static byte[] answer(Socket socket) throws IOException {
        try {
            return socket.getInputStream().readNBytes(4);
        } catch (java.net.SocketException e) {
            return new byte[0];
        }
    }

    @Test
    void withoutTheHeaderNothingGetsIn() throws IOException {
        String before = withListener();
        try (Socket socket = open()) {
            socket.getOutputStream().write(connect("direct-1", "admin", MosquittoResource.PASSWORD));
            assertArrayEquals(new byte[0], answer(socket), "no CONNACK");
        } finally {
            given().contentType(ContentType.JSON).body(Map.of("text", before)).put("/api/config").then().statusCode(200);
        }
    }

    /**
     * Only with TLS up to the proxy: the broker wants the TLS entry in the header, which Traefik never sends. Whether the entry says
     * TLS it does not look at, so the proxy has to take only TLS itself.
     */
    @Test
    void requiringTlsTakesTheTlsEntryOfTheHeader() throws IOException {
        String before = withListener(true);
        given().get("/api/config").then().body("form.listeners.find { it.port == 1884 }.proxyRequireTls", equalTo(true));
        try {
            try (Socket socket = open()) {
                socket.getOutputStream().write(proxyHeader());
                socket.getOutputStream().write(connect("traefik-1", "admin", MosquittoResource.PASSWORD));
                assertArrayEquals(new byte[0], answer(socket), "addresses only, as from Traefik: no CONNACK");
            }
            try (Socket socket = open()) {
                socket.getOutputStream().write(proxyHeader(tlsEntry(0x01)));
                socket.getOutputStream().write(connect("haproxy-1", "admin", MosquittoResource.PASSWORD));
                assertArrayEquals(new byte[] {0x20, 0x02, 0x00, 0x00}, answer(socket), "with the TLS entry, as from HAProxy: accepted");
            }
            try (Socket socket = open()) {
                socket.getOutputStream().write(proxyHeader(tlsEntry(0x00)));
                socket.getOutputStream().write(connect("haproxy-plain-1", "admin", MosquittoResource.PASSWORD));
                // Mosquitto looks only for the entry, not at PP2_CLIENT_SSL in it, and HAProxy sends the entry for every client
                assertArrayEquals(new byte[] {0x20, 0x02, 0x00, 0x00}, answer(socket), "the entry says no TLS, as from HAProxy for a client without TLS: accepted all the same");
            }
        } finally {
            given().contentType(ContentType.JSON).body(Map.of("text", before)).put("/api/config").then().statusCode(200);
        }
    }
}
