package com.dfc.mosquittoui.api;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

/** A real ACME server: Pebble, the test server of Let's Encrypt, which validates against a DNS test server. */
public class PebbleResource implements QuarkusTestResourceLifecycleManager {

    static GenericContainer<?> dns;
    static GenericContainer<?> pebble;
    /** A CA that only takes accounts with an external account binding, like ZeroSSL and Google. */
    static GenericContainer<?> pebbleEab;
    /** Certificates for 90 seconds, so that one comes due during a test. */
    static GenericContainer<?> pebbleShort;
    /**
     * Validates HTTP-01 against the UI of the test: without a DNS test server it resolves through /etc/hosts, where the name below
     * stands for the tunnel of Testcontainers to the port of the UI.
     */
    static GenericContainer<?> pebbleHttp;
    static final String HTTP_DOMAIN = "host.testcontainers.internal";

    /**
     * The directory of a Pebble for acme4j. Its certificate is made for localhost and pebble: in a job container the mapped ports are behind an
     * address, which that name has to stand for.
     */
    static String directory(GenericContainer<?> container) {
        return "acme://pebble/" + (SharedDirectory.inJobContainer() ? "pebble" : container.getHost()) + ":" + container.getMappedPort(14000);
    }

    private static void nameTheHost(String address) {
        try {
            Files.writeString(Path.of("/etc/hosts"), address + " pebble\n", StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Map<String, String> start() {
        Network network = Network.newNetwork();
        dns = new GenericContainer<>("ghcr.io/letsencrypt/pebble-challtestsrv:latest")
                .withNetwork(network)
                .withNetworkAliases("dns")
                .withCommand("-http01", "", "-https01", "", "-tlsalpn01", "", "-doh", "", "-defaultIPv6", "", "-defaultIPv4", "127.0.0.1")
                .withExposedPorts(8055);
        dns.start();
        pebble = new GenericContainer<>("ghcr.io/letsencrypt/pebble:latest")
                .withNetwork(network)
                .withEnv("PEBBLE_VA_NOSLEEP", "1")
                .withEnv("PEBBLE_WFE_NONCEREJECT", "0")
                .withCommand("-config", "test/config/pebble-config.json", "-dnsserver", "dns:8053")
                .withExposedPorts(14000)
                .waitingFor(Wait.forLogMessage(".*Listening on.*", 1));
        pebble.start();
        pebbleEab = new GenericContainer<>("ghcr.io/letsencrypt/pebble:latest")
                .withNetwork(network)
                .withEnv("PEBBLE_VA_NOSLEEP", "1")
                .withEnv("PEBBLE_WFE_NONCEREJECT", "0")
                .withCommand("-config", "test/config/pebble-config-external-account-bindings.json", "-dnsserver", "dns:8053")
                .withExposedPorts(14000)
                .waitingFor(Wait.forLogMessage(".*Listening on.*", 1));
        pebbleEab.start();
        pebbleShort = new GenericContainer<>("ghcr.io/letsencrypt/pebble:latest")
                .withNetwork(network)
                .withEnv("PEBBLE_VA_NOSLEEP", "1")
                .withEnv("PEBBLE_WFE_NONCEREJECT", "0")
                .withCopyFileToContainer(MountableFile.forClasspathResource("pebble-short.json"), "/test/config/pebble-short.json")
                .withCommand("-config", "test/config/pebble-short.json", "-dnsserver", "dns:8053")
                .withExposedPorts(14000)
                .waitingFor(Wait.forLogMessage(".*Listening on.*", 1));
        pebbleShort.start();
        int uiPort = ConfigProvider.getConfig().getOptionalValue("quarkus.http.test-port", Integer.class).orElse(8081);
        Testcontainers.exposeHostPorts(uiPort);
        String httpConfig = """
                {"pebble": {"listenAddress": "0.0.0.0:14000", "managementListenAddress": "0.0.0.0:15000",
                  "certificate": "test/certs/localhost/cert.pem", "privateKey": "test/certs/localhost/key.pem",
                  "httpPort": %d, "tlsPort": 5001, "ocspResponderURL": "", "externalAccountBindingRequired": false}}
                """.formatted(uiPort);
        pebbleHttp = new GenericContainer<>("ghcr.io/letsencrypt/pebble:latest")
                .withNetwork(network)
                .withAccessToHost(true)
                .withEnv("PEBBLE_VA_NOSLEEP", "1")
                .withEnv("PEBBLE_WFE_NONCEREJECT", "0")
                .withCopyToContainer(Transferable.of(httpConfig), "/test/config/pebble-http.json")
                .withCommand("-config", "test/config/pebble-http.json")
                .withExposedPorts(14000)
                .waitingFor(Wait.forLogMessage(".*Listening on.*", 1));
        pebbleHttp.start();
        if (SharedDirectory.inJobContainer()) {
            nameTheHost(pebble.getHost());
        }
        return Map.of();
    }

    @Override
    public void stop() {
        if (pebble != null) {
            pebble.stop();
            pebbleEab.stop();
            pebbleShort.stop();
            pebbleHttp.stop();
            dns.stop();
        }
    }
}
