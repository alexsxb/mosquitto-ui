package com.dfc.mosquittoui.victron;

import com.dfc.mosquittoui.api.Dtos.BridgeForm;
import com.dfc.mosquittoui.api.Dtos.BridgeTopic;
import com.dfc.mosquittoui.api.Dtos.VictronPairing;
import com.dfc.mosquittoui.config.BridgeException;
import com.dfc.mosquittoui.config.BridgeService;
import com.dfc.mosquittoui.config.VictronStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.MqttClientOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * A bridge to a Victron GX device in one step. A device with the Secured profile takes MQTT only over TLS with a token, which it
 * hands out for 120 seconds after pairing mode was turned on (Venus OS 3.80 and later); its certificate is self-signed, so the
 * bridge gets the one the device presents now, and trusts that from then on. Mosquitto needs a file for it, verification of the
 * host name stays off as the certificate is not made for the address. Both read-only and two-way bridges get their data through
 * the keepalive of {@link VictronKeepalive}, so that a read-only bridge has no way back to the device at all. The keepalive trusts
 * the same certificate as the bridge. Should the device present another one, after a reset for example, pairing it again takes
 * over the new certificate and a new token: trust comes again from someone at the device, not from whoever answers on the network.
 */
@ApplicationScoped
public class VictronService {

    private static final Logger LOG = Logger.getLogger(VictronService.class);
    private static final int TLS_PORT = 8883;
    private static final int PLAIN_PORT = 1883;
    private static final int TIMEOUT_MS = 10000;
    private static final int PEM_LINE = 64;
    private static final int KEEPALIVE_SECONDS = 60;
    private static final String ROLE = "mosquittoui";
    /** Retained by the device, the topic is the only thing needed from it. */
    static final String SERIAL_TOPIC = "N/+/system/0/Serial";

    @ConfigProperty(name = "mosquitto-ui.victron.https-port")
    int httpsPort;

    /** Only where the web server of the device is not at its MQTT address, as in the tests on the CI runner. */
    @ConfigProperty(name = "mosquitto-ui.victron.https-host")
    Optional<String> httpsHost;

    @Inject
    BridgeService bridges;

    @Inject
    VictronStore store;

    @Inject
    VictronKeepalive keepalive;

    @Inject
    ObjectMapper mapper;

    @Inject
    Vertx vertx;

    private record Token(String name, String password) {
    }

    /** Trust comes from the person who put this device into pairing mode just now. */
    private static SSLContext trustingContext() throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String type) {
                // not used for client certificates
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String type) {
                // see above
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        return context;
    }

    private static String form(String key, String value) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** The device goes by the first token of a name, so every pairing needs a new device_id; it is alphanumeric. */
    private Token requestToken(String host) {
        byte[] id = new byte[6];
        new SecureRandom().nextBytes(id);
        String body = form("role", ROLE) + "&" + form("device_id", "mui" + HexFormat.of().formatHex(id));
        try {
            HttpsURLConnection connection = (HttpsURLConnection) URI.create("https://" + httpsHost.orElse(host) + ":" + httpsPort + "/auth/generate-token/").toURL().openConnection();
            connection.setSSLSocketFactory(trustingContext().getSocketFactory());
            connection.setHostnameVerifier((name, session) -> true);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_FORBIDDEN) {
                throw new BridgeException("victron_not_pairing", "The device is not in pairing mode");
            }
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new BridgeException("victron_firmware", "The device has no pairing, it needs Venus OS 3.80 or later");
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new BridgeException("victron_unreachable", "The device answered with status " + status);
            }
            JsonNode answer = mapper.readTree(connection.getInputStream());
            if (!answer.path("token_name").isTextual() || !answer.path("password").isTextual()) {
                throw new BridgeException("victron_unreachable", "Unexpected answer of the device");
            }
            return new Token(answer.get("token_name").asText(), answer.get("password").asText());
        } catch (IOException | GeneralSecurityException e) {
            throw new BridgeException("victron_unreachable", "No connection to " + host + ": " + e.getMessage());
        }
    }

    /** The certificates the device presents, trusting whatever it is; nothing is sent to it. */
    String certificates(String host, int port) {
        try (SSLSocket socket = (SSLSocket) trustingContext().getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            socket.startHandshake();
            StringBuilder pem = new StringBuilder();
            Base64.Encoder encoder = Base64.getMimeEncoder(PEM_LINE, new byte[] {'\n'});
            for (Certificate certificate : socket.getSession().getPeerCertificates()) {
                pem.append("-----BEGIN CERTIFICATE-----\n").append(encoder.encodeToString(certificate.getEncoded())).append("\n-----END CERTIFICATE-----\n");
            }
            return pem.toString();
        } catch (IOException | GeneralSecurityException e) {
            throw new BridgeException("victron_unreachable", "No TLS on port " + port + " of " + host + ": " + e.getMessage());
        }
    }

    private String discoverPortalId(String host, int port, String pinned, String username, String password) {
        MqttClient client = MqttClient.create(vertx, options(port, pinned, username, password, "mosquitto-ui-pairing-" + UUID.randomUUID()));
        CompletableFuture<String> portal = new CompletableFuture<>();
        client.publishHandler(message -> portal.complete(message.topicName().split("/")[1]));
        client.connect(port, host).compose(ack -> client.subscribe(SERIAL_TOPIC, 0)).onFailure(portal::completeExceptionally);
        try {
            return portal.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new BridgeException("victron_no_portal", "The device did not tell its portal id, enter it by hand");
        } catch (ExecutionException e) {
            throw new BridgeException("victron_unreachable", "No MQTT on port " + port + " of " + host + ": " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BridgeException("victron_unreachable", "Interrupted");
        } finally {
            if (client.isConnected()) {
                client.disconnect();
            }
        }
    }

    /**
     * For a connection of this application to the device; the bridge itself is the job of the broker.
     *
     * @param pinned the certificate of the device as taken over when pairing, the only one trusted; null for a device without TLS
     */
    static MqttClientOptions options(int port, String pinned, String username, String password, String clientId) {
        MqttClientOptions options = new MqttClientOptions().setClientId(clientId).setCleanSession(true).setKeepAliveInterval(KEEPALIVE_SECONDS);
        if (username != null) {
            options.setUsername(username).setPassword(password);
        }
        if (pinned != null) {
            options.setSsl(true).setTrustOptions(new PemTrustOptions().addCertValue(Buffer.buffer(pinned)));
            // the certificate is not made for the address, see above
            options.setHostnameVerificationAlgorithm("");
        }
        return options;
    }

    private static List<BridgeTopic> topics(VictronPairing pairing) {
        String prefix = pairing.localPrefix() == null || pairing.localPrefix().isBlank() ? null : pairing.localPrefix();
        List<BridgeTopic> topics = new ArrayList<>();
        topics.add(new BridgeTopic("N/#", "in", 0, prefix, null));
        if (pairing.twoWay()) {
            topics.add(new BridgeTopic("R/#", "out", 0, prefix, null));
            topics.add(new BridgeTopic("W/#", "out", 0, prefix, null));
        }
        return topics;
    }

    /**
     * try_private and the state topic stay away from the device, a token may publish only to its own topics. The state stays
     * local, where the page reads it.
     */
    private BridgeForm bridge(VictronPairing pairing, int port, String username, String password) {
        List<String> extra = new ArrayList<>(List.of("notifications_local_only true"));
        if (pairing.secured()) {
            extra.add("bridge_cafile " + store.brokerCertificate(pairing.name()));
            extra.add("bridge_insecure true");
        }
        return new BridgeForm(pairing.name(), pairing.host() + ":" + port, "mosquitto-ui-" + pairing.name(), username, password, false, true, null, KEEPALIVE_SECONDS,
                null, null, topics(pairing), extra, false);
    }

    public void pair(VictronPairing pairing) {
        int port = pairing.port() != null ? pairing.port() : pairing.secured() ? TLS_PORT : PLAIN_PORT;
        // before the device hands out a token for nothing
        bridges.check(bridge(pairing, port, null, null));
        Token token = pairing.secured() ? requestToken(pairing.host()) : new Token(null, null);
        LOG.infof("Paired with %s as %s", pairing.host(), token.name());
        String pinned = pairing.secured() ? certificates(pairing.host(), port) : null;
        if (pinned != null) {
            store.saveCertificate(pairing.name(), pinned);
        }
        String portalId = pairing.portalId() != null && !pairing.portalId().isBlank() ? pairing.portalId()
                : discoverPortalId(pairing.host(), port, pinned, token.name(), token.password());
        bridges.create(bridge(pairing, port, token.name(), token.password()));
        store.put(pairing.name(), portalId);
        keepalive.tick();
    }

    /**
     * Pairs a bridge to a GX device again, which has to be in pairing mode: a new token, and the certificate the device presents
     * now. Everything else of the bridge stays; the broker restarts to take it over. The old token stays on the device, it can be
     * removed there.
     */
    public void pairAgain(String name) {
        BridgeForm bridge = bridges.list().stream().filter(b -> b.name().equals(name)).findFirst()
                .orElseThrow(() -> new BridgeException("not_found", "No bridge " + name));
        if (store.portals().get(name) == null || store.pinned(bridge) == null) {
            throw new BridgeException("validation", "Bridge " + name + " is no paired bridge to a GX device");
        }
        String[] address = bridge.address().split("\\s+")[0].split(":");
        String host = address[0];
        int port = address.length > 1 ? Integer.parseInt(address[1]) : TLS_PORT;
        Token token = requestToken(host);
        LOG.infof("Paired again with %s as %s", host, token.name());
        store.replacePinned(bridge, certificates(host, port));
        bridges.update(name, new BridgeForm(bridge.name(), bridge.address(), bridge.remoteClientId(), token.name(), token.password(), bridge.tryPrivate(),
                bridge.cleanSession(), bridge.startType(), bridge.keepalive(), bridge.restartTimeout(), bridge.protocolVersion(), bridge.topics(),
                bridge.extraOptions(), bridge.disabled()), true);
        keepalive.reconnect(name);
    }
}
