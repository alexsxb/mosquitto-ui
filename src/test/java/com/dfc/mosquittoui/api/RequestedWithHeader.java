package com.dfc.mosquittoui.api;

import io.quarkus.test.junit.callback.QuarkusTestBeforeEachCallback;
import io.quarkus.test.junit.callback.QuarkusTestMethodContext;
import io.restassured.RestAssured;
import io.restassured.filter.Filter;

/**
 * Every test request carries the header of the frontend, without which CsrfFilter refuses changes; CsrfTest goes without
 * it. A filter rather than a request specification: that would bring its own port and override the one of the test.
 */
public class RequestedWithHeader implements QuarkusTestBeforeEachCallback {

    private static final Filter HEADER = (request, response, context) -> {
        if (!request.getHeaders().hasHeaderWithName(CsrfFilter.HEADER)) {
            request.header(CsrfFilter.HEADER, "JavaScript");
        }
        return context.next(request, response);
    };

    @Override
    public void beforeEach(QuarkusTestMethodContext context) {
        if (!RestAssured.filters().contains(HEADER)) {
            RestAssured.filters(HEADER);
        }
    }
}
