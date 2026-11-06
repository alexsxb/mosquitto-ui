package com.dfc.mosquittoui.certificate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * The PowerDNS API, as served by PowerDNS itself and by pdnsadmin below /api/v1: the zone is the longest suffix of the
 * name that the server knows, the record is replaced as a whole. A wildcard shares its name with its domain, so one
 * order can bring two values for a name.
 */
final class PdnsChallenge implements DnsChallenge {

    private static final Logger LOG = Logger.getLogger(PdnsChallenge.class);
    private static final String SERVER = "/api/v1/servers/localhost";
    private static final int TTL = 60;

    private final String baseUrl;
    private final String token;
    private final ObjectMapper mapper;
    private final Map<String, List<String>> values = new HashMap<>();

    PdnsChallenge(String url, String token, ObjectMapper mapper) {
        this.baseUrl = url.replaceAll("/+$", "");
        this.token = token;
        this.mapper = mapper;
    }

    private HttpRequest.Builder request(String path) {
        return DnsHttp.request(baseUrl + SERVER + path).header("X-API-Key", token);
    }

    private static String encoded(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8);
    }

    private String zoneOf(String domain) throws IOException, InterruptedException {
        String candidate = domain + ".";
        while (candidate.indexOf('.') < candidate.length() - 1) {
            String zones = DnsHttp.send(request("/zones?zone=" + encoded(candidate)).GET().build());
            if (!mapper.readTree(zones).isEmpty()) {
                return candidate;
            }
            candidate = candidate.substring(candidate.indexOf('.') + 1);
        }
        throw new IOException("No zone found for " + domain);
    }

    private void patch(String zone, ObjectNode rrset) throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        body.putArray("rrsets").add(rrset);
        DnsHttp.send(request("/zones/" + encoded(zone))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString()))
                .build());
    }

    /** Backends without a primary role, like OVH or Hetzner, do not know it; the record is there without. */
    private void notifySecondaries(String zone) throws InterruptedException {
        try {
            DnsHttp.send(request("/zones/" + encoded(zone) + "/notify").PUT(HttpRequest.BodyPublishers.noBody()).build());
        } catch (IOException e) {
            LOG.debugf("No notify for %s: %s", zone, e.getMessage());
        }
    }

    @Override
    public void set(String domain, String value) throws Exception {
        String zone = zoneOf(domain);
        List<String> current = values.computeIfAbsent(domain, d -> new ArrayList<>());
        current.add(value);
        ObjectNode rrset = mapper.createObjectNode().put("changetype", "REPLACE").put("name", "_acme-challenge." + domain + ".").put("type", "TXT").put("ttl", TTL);
        ArrayNode records = rrset.putArray("records");
        current.forEach(txt -> records.addObject().put("content", "\"" + txt + "\"").put("disabled", false));
        patch(zone, rrset);
        notifySecondaries(zone);
    }

    @Override
    public void clear(String domain) throws Exception {
        if (values.remove(domain) == null) {
            return;
        }
        String zone = zoneOf(domain);
        patch(zone, mapper.createObjectNode().put("changetype", "DELETE").put("name", "_acme-challenge." + domain + ".").put("type", "TXT"));
        notifySecondaries(zone);
    }
}
