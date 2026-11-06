package com.dfc.mosquittoui.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/** Stands in for pdnsadmin with both of its APIs, the records end up in the DNS server that Pebble asks. */
@Path("/fake-dns")
@Consumes(MediaType.WILDCARD)
public class FakeDnsResource {

    static final String KEY = "secret-key";
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @Inject
    ObjectMapper mapper;

    private void dns(String path, Object body) throws IOException, InterruptedException {
        String url = "http://" + PebbleResource.dns.getHost() + ":" + PebbleResource.dns.getMappedPort(8055) + path;
        HTTP.send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @POST
    @Path("/acme-dns/update")
    public Map<String, String> update(@HeaderParam("X-Api-User") String user, @HeaderParam("X-Api-Key") String key, JsonNode request) throws Exception {
        if (!KEY.equals(key) || !user.equals(request.get("subdomain").asText())) {
            throw new WebApplicationException(401);
        }
        dns("/set-txt", Map.of("host", "_acme-challenge." + user + ".", "value", request.get("txt").asText()));
        return Map.of("txt", request.get("txt").asText());
    }

    @GET
    @Path("/pdns/api/v1/servers/localhost/zones")
    public List<Map<String, String>> zones(@HeaderParam("X-API-Key") String key, @QueryParam("zone") String zone) {
        if (!KEY.equals(key)) {
            throw new WebApplicationException(401);
        }
        return "example.test.".equals(zone) ? List.of(Map.of("name", zone)) : List.of();
    }

    @PATCH
    @Path("/pdns/api/v1/servers/localhost/zones/{zone}")
    public void patch(@PathParam("zone") String zone, JsonNode body) throws Exception {
        if (!"example.test.".equals(zone)) {
            throw new WebApplicationException(404);
        }
        JsonNode rrset = body.get("rrsets").get(0);
        String name = rrset.get("name").asText();
        dns("/clear-txt", Map.of("host", name));
        if (rrset.get("changetype").asText().equals("REPLACE")) {
            for (JsonNode record : rrset.get("records")) {
                dns("/set-txt", Map.of("host", name, "value", record.get("content").asText().replace("\"", "")));
            }
        }
    }

    @PUT
    @Path("/pdns/api/v1/servers/localhost/zones/{zone}/notify")
    public void notifySecondaries() {
        // like OVH or Hetzner, which have no such thing
        throw new WebApplicationException(404);
    }
}
