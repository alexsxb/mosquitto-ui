package com.dfc.mosquittoui.certificate;

import com.dfc.mosquittoui.notify.MailService;
import com.dfc.mosquittoui.notify.Notice;
import com.dfc.mosquittoui.api.Dtos.CertificateInfo;
import com.dfc.mosquittoui.api.Dtos.CertificateJob;
import com.dfc.mosquittoui.api.Dtos.CertificateSetForm;
import com.dfc.mosquittoui.api.Dtos.CertificateSetView;
import com.dfc.mosquittoui.api.Dtos.CertificateSettings;
import com.dfc.mosquittoui.api.Dtos.CertificateView;
import com.dfc.mosquittoui.broker.DynsecClient;
import com.dfc.mosquittoui.certificate.CertificateStore.StoredSet;
import com.dfc.mosquittoui.config.AgentException;
import com.dfc.mosquittoui.config.BrokerAgent;
import com.dfc.mosquittoui.config.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.shredzone.acme4j.Account;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.util.KeyPairUtils;

/**
 * The TLS certificates of the broker, as sets of settings and certificate of which one is active. Ordered with DNS-01 through
 * the API of the DNS server, in the background as the records need time to spread, and handed to the agent, which keeps the key
 * of each set and reloads the broker without disconnecting clients.
 */
@ApplicationScoped
public class CertificateService {

    private static final Logger LOG = Logger.getLogger(CertificateService.class);
    private static final Duration VALIDATION_TIMEOUT = Duration.ofMinutes(3);
    private static final int ACCOUNT_KEY_BITS = 2048;
    private static final String EC_CURVE = "secp256r1";
    /** Renew in the last third of the lifetime, which also fits shorter certificates. */
    private static final int RENEWAL_DIVISOR = 3;
    private static final int DEFAULT_TLS_PORT = 8883;

    @Inject
    CertificateStore store;

    @Inject
    BrokerAgent agent;

    @Inject
    DynsecClient dynsec;

    @Inject
    ConfigService config;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "mosquitto-ui.cert-dir")
    String certDir;

    @Inject
    MailService mail;

    /** A set with all that belongs to it, for a backup; certificate and key are null where the broker holds them or there are none. */
    public record SetBackup(StoredSet set, String accountKey, String chain, String key) {
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile String step;
    private volatile String error;
    private volatile String jobSet;

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private CertificateSettings withoutSecrets(CertificateSettings s) {
        return s == null ? null : new CertificateSettings(s.provider(), s.url(), null, s.apiUser(), s.subdomain(), s.domains(), s.email(),
                s.directory(), s.keyType(), s.propagationSeconds(), s.autoRenew(), s.acceptTerms(), s.tlsListener(), s.tlsPort(), s.eabKeyId(), null);
    }

    public CertificateView view() {
        String active = store.activeId();
        List<CertificateSetView> sets = store.sets().stream().map(set -> view(set, active)).toList();
        return new CertificateView(agent.isAvailable(), sets, info(store.certificate(active)),
                new CertificateJob(running.get(), running.get() ? step : null, error, jobSet), certfile(), keyfile(), config.tlsPort(certfile()));
    }

    private CertificateSetView view(StoredSet set, String active) {
        CertificateSettings settings = set.settings();
        return new CertificateSetView(set.id(), set.name(), set.id().equals(active), withoutSecrets(settings), !blank(settings.token()),
                !blank(settings.eabHmacKey()), info(store.certificate(set.id())));
    }

    private String certfile() {
        return certDir + "/fullchain.pem";
    }

    private String keyfile() {
        return certDir + "/privkey.pem";
    }

    private StoredSet set(String id) {
        StoredSet set = store.set(id);
        if (set == null) {
            throw new NotFoundException();
        }
        return set;
    }

    private void refuseTakenName(String name, String id) {
        if (store.sets().stream().anyMatch(set -> !set.id().equals(id) && set.name().equalsIgnoreCase(name))) {
            throw new CertificateException("set_exists", "A set with this name exists already");
        }
    }

    /** Without a token or a key in the request, the stored one stays. */
    private static CertificateSettings merged(CertificateSettings settings, CertificateSettings old) {
        String token = blank(settings.token()) ? (old == null ? null : old.token()) : settings.token().strip();
        String eabKey = blank(settings.eabHmacKey()) ? (old == null ? null : old.eabHmacKey()) : settings.eabHmacKey().strip();
        return new CertificateSettings(settings.provider(), settings.url().strip(), token, settings.apiUser(), settings.subdomain(),
                settings.domains().stream().map(String::strip).toList(), settings.email(), settings.directory().strip(), settings.keyType(),
                settings.propagationSeconds(), settings.autoRenew(), settings.acceptTerms(), settings.tlsListener(), settings.tlsPort(),
                blank(settings.eabKeyId()) ? null : settings.eabKeyId().strip(), eabKey);
    }

    public CertificateSetView createSet(CertificateSetForm form) {
        String name = form.name().strip();
        refuseTakenName(name, null);
        StoredSet set = new StoredSet(CertificateStore.newId(), name, merged(form.settings(), null));
        store.save(set);
        return view(set, store.activeId());
    }

    public void updateSet(String id, CertificateSetForm form) {
        StoredSet old = set(id);
        String name = form.name().strip();
        refuseTakenName(name, id);
        store.save(new StoredSet(id, name, merged(form.settings(), old.settings())));
    }

    /** The active set stays, the configuration uses its certificate. */
    public void deleteSet(String id) {
        set(id);
        if (id.equals(store.activeId())) {
            throw new CertificateException("set_active", "The active set cannot be deleted");
        }
        if (!running.compareAndSet(false, true)) {
            throw new CertificateException("certificate_running", "An order is running");
        }
        try {
            agent.call("cert-delete", id);
            store.delete(id);
            if (id.equals(jobSet)) {
                error = null;
                jobSet = null;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            running.set(false);
        }
    }

    /**
     * Puts the certificate of a set in use. The configuration stays with the same paths, only a listener on another port and
     * another key type need a restart.
     */
    public void activate(String id) {
        StoredSet set = set(id);
        String active = store.activeId();
        if (id.equals(active)) {
            return;
        }
        X509Certificate certificate = store.certificate(id);
        if (certificate == null) {
            throw new CertificateException("no_certificate", "This set has no certificate yet");
        }
        if (Instant.now().isAfter(certificate.getNotAfter().toInstant())) {
            throw new CertificateException("certificate_expired", "The certificate of this set has expired");
        }
        if (!running.compareAndSet(false, true)) {
            throw new CertificateException("certificate_running", "An order is running");
        }
        step = null;
        try {
            boolean restart = !sameKeyType(store.certificate(active), certificate);
            agent.call("cert-activate", restart ? "restart" : "reload", id, active == null ? "" : active);
            store.activate(id);
            if (restart) {
                dynsec.awaitConnected();
            }
            if (set.settings().tlsListener()) {
                StoredSet previous = active == null ? null : store.set(active);
                setUpListener(port(set.settings()), previous == null || !previous.settings().tlsListener() ? null : port(previous.settings()));
            }
        } finally {
            running.set(false);
        }
    }

    private static int port(CertificateSettings settings) {
        return settings.tlsPort() == null ? DEFAULT_TLS_PORT : settings.tlsPort();
    }

    private static boolean sameKeyType(X509Certificate old, X509Certificate other) {
        return old == null || old.getPublicKey() instanceof RSAPublicKey == other.getPublicKey() instanceof RSAPublicKey;
    }

    private static Instant renewalFrom(X509Certificate certificate) {
        Instant start = certificate.getNotBefore().toInstant();
        Instant end = certificate.getNotAfter().toInstant();
        return end.minus(Duration.between(start, end).dividedBy(RENEWAL_DIVISOR));
    }

    private static boolean renewalDue(X509Certificate certificate) {
        return Instant.now().isAfter(renewalFrom(certificate));
    }

    private static CertificateInfo info(X509Certificate certificate) {
        if (certificate == null) {
            return null;
        }
        List<String> domains = new ArrayList<>();
        try {
            if (certificate.getSubjectAlternativeNames() != null) {
                certificate.getSubjectAlternativeNames().forEach(name -> domains.add(String.valueOf(name.get(1))));
            }
        } catch (CertificateParsingException e) {
            LOG.debug("No alternative names", e);
        }
        return new CertificateInfo(commonName(certificate.getSubjectX500Principal().getName()), domains,
                commonName(certificate.getIssuerX500Principal().getName()), certificate.getNotBefore().toInstant(),
                certificate.getNotAfter().toInstant(), Duration.between(Instant.now(), certificate.getNotAfter().toInstant()).toDays(),
                certificate.getSerialNumber().toString(16), renewalDue(certificate), renewalFrom(certificate), certificate.getPublicKey() instanceof RSAPublicKey ? "RSA" : "EC");
    }

    private static String commonName(String distinguishedName) {
        try {
            return new LdapName(distinguishedName).getRdns().stream().filter(rdn -> rdn.getType().equalsIgnoreCase("CN")).map(Rdn::getValue)
                    .map(String::valueOf).findFirst().orElse(distinguishedName);
        } catch (javax.naming.InvalidNameException e) {
            return distinguishedName;
        }
    }

    /**
     * Back to no certificates: their listener goes first, as the broker would not start without the files. The CA is not told, a
     * certificate stays valid until it expires.
     */
    public void reset() {
        if (!running.compareAndSet(false, true)) {
            throw new CertificateException("certificate_running", "An order is running");
        }
        try {
            config.removeTlsListeners(certfile());
            agent.call("cert-remove");
            store.clear();
            error = null;
            jobSet = null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            running.set(false);
        }
    }

    /** Starts an order for a set; the result shows in the view. A set that is not active only gets it stored. */
    public void issue(String id) {
        issue(id, false);
    }

    private void issue(String id, boolean renewal) {
        CertificateSettings settings = set(id).settings();
        if (settings.token() == null) {
            throw new CertificateException("not_configured", "No API token for the certificate yet");
        }
        if (!settings.acceptTerms()) {
            throw new CertificateException("terms_required", "The terms of service of the CA have to be accepted");
        }
        // before anything counts against the rate limits of the CA
        if (!agent.isAvailable()) {
            throw new AgentException("The agent in the broker container does not answer", true);
        }
        if (!running.compareAndSet(false, true)) {
            throw new CertificateException("certificate_running", "An order is already running");
        }
        error = null;
        jobSet = id;
        executor.submit(() -> {
            try {
                order(id, settings);
                LOG.infof("Certificate issued for %s", settings.domains());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
                LOG.errorf("Certificate for %s failed: %s", settings.domains(), error);
                if (renewal) {
                    // nobody is looking at the page, and the hint on it is gone with the next start of the UI
                    mail.notifyFailure(Notice.CERTIFICATE, "mosquitto-ui: renewal of the certificate failed", "The certificate for " + settings.domains() + " could not be renewed:\n\n" + error);
                }
            } finally {
                running.set(false);
            }
        });
    }

    /** A certificate that is due, of the active set only: nothing happens before the first one was requested by hand. */
    @Scheduled(every = "{mosquitto-ui.certificate.renewal-check}", delayed = "{mosquitto-ui.certificate.renewal-delay}")
    void renew() {
        String active = store.activeId();
        StoredSet set = active == null ? null : store.set(active);
        X509Certificate certificate = store.certificate(active);
        if (set != null && set.settings().autoRenew() && certificate != null && renewalDue(certificate) && !running.get()) {
            LOG.info("Renewing the certificate");
            try {
                issue(active, true);
            } catch (RuntimeException e) {
                // before the order even started: without this it would only be in the log, until the certificate runs out
                error = e.getMessage();
                jobSet = active;
                LOG.errorf("Certificate for %s not renewed: %s", set.settings().domains(), error);
                mail.notifyFailure(Notice.CERTIFICATE, "mosquitto-ui: renewal of the certificate failed", "The certificate for " + set.settings().domains() + " could not be renewed:\n\n" + error);
            }
        }
    }

    private DnsChallenge dns(CertificateSettings settings) {
        return settings.provider().equals("PDNS")
                ? new PdnsChallenge(settings.url(), settings.token(), mapper)
                : new AcmeDnsChallenge(settings.url(), settings.token(), settings.apiUser(), settings.subdomain(), mapper);
    }

    private Account account(String id, CertificateSettings settings) throws AcmeException, IOException {
        String pem = store.accountKey(id);
        KeyPair key;
        if (pem == null) {
            key = KeyPairUtils.createKeyPair(ACCOUNT_KEY_BITS);
            StringWriter writer = new StringWriter();
            KeyPairUtils.writeKeyPair(key, writer);
            store.saveAccountKey(id, writer.toString());
        } else {
            key = KeyPairUtils.readKeyPair(new StringReader(pem));
        }
        AccountBuilder builder = new AccountBuilder().agreeToTermsOfService().useKeyPair(key);
        if (settings.email() != null && !settings.email().isBlank()) {
            builder.addEmail(settings.email().strip());
        }
        // ZeroSSL and Google only know accounts that were created with them
        if (!blank(settings.eabKeyId()) && !blank(settings.eabHmacKey())) {
            builder.withKeyIdentifier(settings.eabKeyId(), settings.eabHmacKey());
        }
        return builder.create(new Session(settings.directory()));
    }

    private void order(String id, CertificateSettings settings) throws Exception {
        step = "account";
        Account account = account(id, settings);
        step = "order";
        Order order = account.newOrder().domains(settings.domains()).create();

        List<Authorization> pending = order.getAuthorizations().stream().filter(auth -> auth.getStatus() != Status.VALID).toList();
        DnsChallenge dns = dns(settings);
        Set<String> names = new LinkedHashSet<>();
        try {
            step = "dns";
            List<Dns01Challenge> challenges = new ArrayList<>();
            for (Authorization authorization : pending) {
                Dns01Challenge challenge = authorization.findChallenge(Dns01Challenge.class)
                        .orElseThrow(() -> new AcmeException("The CA offers no DNS-01 challenge"));
                String domain = authorization.getIdentifier().getDomain();
                names.add(domain);
                dns.set(domain, challenge.getDigest());
                challenges.add(challenge);
            }
            if (!challenges.isEmpty()) {
                step = "propagation";
                Thread.sleep(Duration.ofSeconds(settings.propagationSeconds()));
            }
            step = "validation";
            for (Dns01Challenge challenge : challenges) {
                challenge.trigger();
            }
            for (Dns01Challenge challenge : challenges) {
                if (challenge.waitForCompletion(VALIDATION_TIMEOUT) != Status.VALID) {
                    throw new AcmeException("Validation failed: " + challenge.getError().map(Object::toString).orElse("no reason given"));
                }
            }
        } finally {
            for (String name : names) {
                try {
                    dns.clear(name);
                } catch (IOException e) {
                    LOG.warnf("Challenge of %s not removed: %s", name, e.getMessage());
                }
            }
        }

        step = "certificate";
        order.waitUntilReady(VALIDATION_TIMEOUT);
        KeyPair key = settings.keyType().equals("RSA") ? KeyPairUtils.createKeyPair(ACCOUNT_KEY_BITS) : KeyPairUtils.createECKeyPair(EC_CURVE);
        order.execute(key);
        if (order.waitForCompletion(VALIDATION_TIMEOUT) != Status.VALID) {
            throw new AcmeException("The CA did not issue the certificate: " + order.getError().map(Object::toString).orElse("no reason given"));
        }
        StringWriter chain = new StringWriter();
        order.getCertificate().writeCertificate(chain);
        String active = store.activeId();
        // a broker without a certificate takes the first one that comes, as before there were sets
        boolean goesLive = active == null || active.equals(id);
        install(id, chain.toString(), key, goesLive);
        if (goesLive && settings.tlsListener()) {
            step = "listener";
            setUpListener(port(settings), null);
        }
    }

    /** The broker has the certificate at this point; what fails here is only the listener, and a later order tries again. */
    private void setUpListener(int port, Integer replacedPort) {
        try {
            config.ensureTlsListener(port, certfile(), keyfile(), replacedPort);
        } catch (AgentException e) {
            throw new CertificateException("listener_failed", "The certificate is installed, but not the TLS listener on port " + port + ": " + e.getMessage());
        }
    }

    private void handOver(String chain, String key) throws IOException {
        Files.deleteIfExists(agent.controlFile("privkey.pem"));
        // only the agent, which runs as root, gets to read the key
        Files.createFile(agent.controlFile("privkey.pem"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(agent.controlFile("privkey.pem"), key, StandardOpenOption.WRITE);
        Files.writeString(agent.controlFile("fullchain.pem"), chain);
    }

    public String activeSet() {
        return store.activeId();
    }

    /** All sets; the keys of certificates are with the broker and come from there. */
    public List<SetBackup> exportSets() {
        return store.sets().stream().map(set -> new SetBackup(set, store.accountKey(set.id()), null, null)).toList();
    }

    /** Takes over the sets of a backup in place of those here. The broker restarts with the certificate that was active then. */
    public void restoreSets(String active, List<SetBackup> sets) {
        if (!running.compareAndSet(false, true)) {
            throw new CertificateException("certificate_running", "An order is running");
        }
        try {
            String previous = store.activeId();
            List<String> old = store.sets().stream().map(StoredSet::id).toList();
            for (SetBackup backup : sets) {
                String id = backup.set().id();
                if (backup.chain() != null && backup.key() != null) {
                    handOver(backup.chain(), backup.key());
                    agent.call("cert-install", "store", id);
                    store.saveCertificate(id, backup.chain());
                }
                if (backup.accountKey() != null) {
                    store.saveAccountKey(id, backup.accountKey());
                }
                store.save(backup.set());
            }
            boolean restorable = sets.stream().anyMatch(backup -> backup.set().id().equals(active) && backup.key() != null);
            String live = restorable ? active : previous;
            if (restorable) {
                agent.call("cert-activate", "restart", live, previous == null || previous.equals(live) ? "" : previous);
                store.activate(live);
                dynsec.awaitConnected();
            }
            for (String id : old) {
                if (!id.equals(live) && sets.stream().noneMatch(backup -> backup.set().id().equals(id))) {
                    agent.call("cert-delete", id);
                    store.delete(id);
                }
            }
            error = null;
            jobSet = null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            running.set(false);
        }
    }

    private void install(String id, String chain, KeyPair key, boolean goesLive) throws IOException {
        step = "install";
        StringWriter pem = new StringWriter();
        KeyPairUtils.writeKeyPair(key, pem);
        handOver(chain, pem.toString());
        if (!goesLive) {
            agent.call("cert-install", "store", id);
            store.saveCertificate(id, chain);
            return;
        }
        // A reload adds a key of another type next to the old one in the TLS context, and the old certificate stays in use
        boolean restart = !sameKeyType(store.certificate(store.activeId()), CertificateStore.parse(chain));
        agent.call("cert-install", restart ? "restart" : "reload", id);
        store.saveCertificate(id, chain);
        store.activate(id);
        if (restart) {
            dynsec.awaitConnected();
        }
    }
}
