package com.dfc.mosquittoui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** Plain HTTP requests, as another site could make them: RestAssured would add the header of the frontend. */
@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@TestSecurity(user = "alice", roles = "mosquitto-admin")
class CsrfTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TestHTTPResource("/")
    URI base;

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void changeWithoutHeaderIsRefused() throws Exception {
        // a form upload needs no CORS preflight, so another site of the same domain could send it with the session cookie
        String boundary = "csrf";
        String form = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"request\"\r\n\r\n"
                + "{\"sections\":[\"ACCESS\"],\"trustUnsigned\":true}\r\n--" + boundary + "--\r\n";
        HttpResponse<String> restore = send(HttpRequest.newBuilder(base.resolve("api/backup/restore"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(form)));
        assertEquals(403, restore.statusCode());
        assertTrue(restore.body().contains("\"code\":\"csrf\""), restore.body());

        HttpResponse<String> delete = send(HttpRequest.newBuilder(base.resolve("api/clients/nobody")).DELETE());
        assertEquals(403, delete.statusCode());
    }

    @Test
    void changeWithHeaderReachesTheEndpoint() throws Exception {
        HttpResponse<String> key = send(HttpRequest.newBuilder(base.resolve("api/backup/gpg/key"))
                .header("Content-Type", "text/plain")
                .header(CsrfFilter.HEADER, "JavaScript")
                .POST(HttpRequest.BodyPublishers.ofString("not a key")));
        assertEquals(400, key.statusCode(), key.body());
    }

    @Test
    void readingNeedsNoHeader() throws Exception {
        assertEquals(200, send(HttpRequest.newBuilder(base.resolve("api/system/info")).GET()).statusCode());
    }
}
