package com.dfc.mosquittoui.certificate;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpRequest;

/**
 * The acme-dns protocol: POST /update with X-Api-User and X-Api-Key. For pdnsadmin the user names the domain of an ACME
 * key or DynDNS host, so without further settings the domain is both user and subdomain; a real acme-dns server needs the
 * values of its registration. The protocol cannot remove records, the server keeps the last two values of a name.
 */
final class AcmeDnsChallenge implements DnsChallenge {

    private final String baseUrl;
    private final String token;
    private final String user;
    private final String subdomain;
    private final ObjectMapper mapper;

    AcmeDnsChallenge(String url, String token, String user, String subdomain, ObjectMapper mapper) {
        this.baseUrl = url.replaceAll("/+$", "");
        this.token = token;
        this.user = user;
        this.subdomain = subdomain;
        this.mapper = mapper;
    }

    private static String orDomain(String value, String domain) {
        return value == null || value.isBlank() ? domain : value;
    }

    @Override
    public void set(String domain, String value) throws Exception {
        String body = mapper.createObjectNode().put("subdomain", orDomain(subdomain, domain)).put("txt", value).toString();
        DnsHttp.send(DnsHttp.request(baseUrl + "/update")
                .header("X-Api-User", orDomain(user, domain))
                .header("X-Api-Key", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    @Override
    public void clear(String domain) {
        // not part of the protocol
    }
}
