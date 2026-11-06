package com.dfc.mosquittoui.certificate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** HTTP calls to the DNS API. Tokens are in headers, errors only name the URL and the answer. */
final class DnsHttp {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private static final int MAX_ERROR_BODY = 200;

    private DnsHttp() {
    }

    static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT);
    }

    /** The body of a successful answer. */
    static String send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            String body = response.body();
            throw new IOException(request.method() + " " + request.uri() + ": HTTP " + response.statusCode() + " "
                    + (body.length() > MAX_ERROR_BODY ? body.substring(0, MAX_ERROR_BODY) : body));
        }
        return response.body();
    }
}
