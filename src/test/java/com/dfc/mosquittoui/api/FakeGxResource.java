package com.dfc.mosquittoui.api;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Stands in for a GX device with the Secured profile, as far as it is known from the sources in VictronService: TLS on 8883 with a
 * self-signed certificate that is not made for the address, and tokens over HTTPS while in pairing mode. The MQTT side is a real
 * Mosquitto; it takes any login, so that only the behaviour of the UI and the bridge is tested here, not the rules of FlashMQ.
 */
public class FakeGxResource implements QuarkusTestResourceLifecycleManager {

    static final String PORTAL_ID = "c0619ab9a0b1";
    static final AtomicBoolean PAIRING_MODE = new AtomicBoolean();
    static final List<String> REQUESTS = new ArrayList<>();

    static GenericContainer<?> device;
    private HttpsServer https;

    /**
     * Reachable from the tests and from the broker in its container. In a job container the HTTPS side, which is in the tests, is not behind this
     * address, see start.
     */
    static String gateway() {
        if (SharedDirectory.inJobContainer()) {
            return device.getHost();
        }
        return device.getContainerInfo().getNetworkSettings().getNetworks().values().iterator().next().getGateway();
    }

    static int mqttPort() {
        return device.getMappedPort(8883);
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der) + "\n-----END " + type + "-----\n";
    }

    private static byte[] read(java.io.InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toByteArray();
    }

    /** A self-signed certificate as a GX device makes one, with its key. */
    private record Identity(KeyPair key, X509Certificate certificate) {
    }

    private static Identity identity() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair key = generator.generateKeyPair();
        X500Name name = new X500Name("CN=venus.local");
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(new SecureRandom().nextLong(1, Long.MAX_VALUE)),
                Date.from(Instant.now().minus(Duration.ofDays(1))), Date.from(Instant.now().plus(Duration.ofDays(30))), name, key.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(key.getPrivate())));
        return new Identity(key, certificate);
    }

    /**
     * The device makes itself a new certificate, as after a reset: the broker on it starts again with it, which ends all
     * connections. Returns the new certificate as PEM.
     */
    static String newCertificate() throws Exception {
        Identity identity = identity();
        String certificate = pem("CERTIFICATE", identity.certificate().getEncoded());
        device.copyFileToContainer(org.testcontainers.images.builder.Transferable.of(certificate), "/gx/server.crt");
        device.copyFileToContainer(org.testcontainers.images.builder.Transferable.of(pem("PRIVATE KEY", identity.key().getPrivate().getEncoded()).getBytes(StandardCharsets.UTF_8), 0644), "/gx/server.key");
        device.execInContainer("killall", "mosquitto");
        return certificate;
    }

    @Override
    public Map<String, String> start() {
        try {
            Identity identity = identity();
            KeyPair key = identity.key();
            X509Certificate certificate = identity.certificate();

            Path dir = Files.createTempDirectory("fake-gx", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
            Files.writeString(dir.resolve("server.crt"), pem("CERTIFICATE", certificate.getEncoded()));
            Files.writeString(dir.resolve("server.key"), pem("PRIVATE KEY", key.getPrivate().getEncoded()));
            Files.setPosixFilePermissions(dir.resolve("server.key"), PosixFilePermissions.fromString("rw-r--r--"));
            Files.writeString(dir.resolve("mosquitto.conf"), "listener 8883\nallow_anonymous true\ncertfile /gx/server.crt\nkeyfile /gx/server.key\n");
            device = new GenericContainer<>("eclipse-mosquitto:2.1.2-alpine")
                    .withNetwork(Network.SHARED)
                    .withCopyFileToContainer(MountableFile.forHostPath(dir), "/gx")
                    // in a loop, so that it can start again with a new certificate on the same port, see newCertificate
                    .withCommand("sh", "-c", "while :; do mosquitto -c /gx/mosquitto.conf; sleep 0.2; done")
                    .withExposedPorts(8883)
                    .waitingFor(Wait.forLogMessage(".*mosquitto version .* running.*", 1));
            device.start();

            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("gx", key.getPrivate(), new char[0], new java.security.cert.Certificate[] {certificate});
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, new char[0]);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            https = HttpsServer.create(new InetSocketAddress(0), 0);
            https.setHttpsConfigurator(new HttpsConfigurator(context));
            https.createContext("/auth/generate-token/", exchange -> {
                String body = new String(read(exchange.getRequestBody()), StandardCharsets.UTF_8);
                REQUESTS.add(body);
                byte[] answer = ("{\"token_name\":\"token/mosquittoui/" + REQUESTS.size() + "\",\"password\":\"gx-token-password\"}").getBytes(StandardCharsets.UTF_8);
                if (PAIRING_MODE.get()) {
                    exchange.sendResponseHeaders(200, answer.length);
                    exchange.getResponseBody().write(answer);
                } else {
                    exchange.sendResponseHeaders(403, -1);
                }
                exchange.close();
            });
            https.start();
            Map<String, String> config = new java.util.HashMap<>(Map.of("mosquitto-ui.victron.https-port", String.valueOf(https.getAddress().getPort()),
                    "mosquitto-ui.victron.keepalive-interval", "2s"));
            if (SharedDirectory.inJobContainer()) {
                config.put("mosquitto-ui.victron.https-host", "localhost");
            }
            return config;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void stop() {
        if (https != null) {
            https.stop(0);
        }
        if (device != null) {
            device.stop();
        }
    }
}
