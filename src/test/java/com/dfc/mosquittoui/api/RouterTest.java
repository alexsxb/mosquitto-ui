package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The kind of internet connection, from the UPnP status of a router; answers as a FRITZ!Box gave them. */
@QuarkusTest
@TestSecurity(user = "alice", roles = "mosquitto-admin")
class RouterTest {

    private static final Map<String, String> CONNECTED = Map.of("NewConnectionStatus", "Connected", "NewLastConnectionError", "ERROR_NONE", "NewUptime", "14255");
    private static final Map<String, String> NO_IPV6 = Map.of("NewExternalIPv6Address", "", "NewPrefixLength", "0");
    private static final Map<String, String> NO_PREFIX = Map.of("NewIPv6Prefix", "::", "NewPrefixLength", "0");

    @AfterEach
    void reset() {
        FakeIgdResource.answers = Map.of();
        FakeIgdResource.secondDescription = true;
    }

    @Test
    void mobileConnectionBehindTheNatOfTheProvider() {
        FakeIgdResource.answers = Map.of(
                "GetStatusInfo", CONNECTED,
                "GetExternalIPAddress", Map.of("NewExternalIPAddress", "10.30.192.101"),
                "X_AVM_DE_GetDsliteStatus", Map.of("NewX_AVM_DE_DsliteStatus", "0"),
                "GetAddonInfos", Map.of("NewUpnpControlEnabled", "0", "NewX_AVM_DE_WANAccessType", "LTE"),
                "X_AVM_DE_GetExternalIPv6Address", NO_IPV6,
                "X_AVM_DE_GetIPv6Prefix", NO_PREFIX);
        given().queryParam("host", "localhost").get("/api/router").then().statusCode(200)
                .body("model", equalTo("FRITZ!Box 6860 5G"))
                .body("accessType", equalTo("LTE"))
                .body("connectionStatus", equalTo("Connected"))
                .body("externalIPv4", equalTo("10.30.192.101"))
                .body("ipv4Kind", equalTo("PRIVATE"))
                .body("dsLite", equalTo(false))
                .body("externalIPv6", nullValue())
                .body("ipv6Prefix", nullValue())
                .body("portMappingAllowed", equalTo(false))
                .body("ipv6Firewall", equalTo(true));
    }

    @Test
    void dsLiteWithIpv6() {
        FakeIgdResource.answers = Map.of(
                "GetStatusInfo", CONNECTED,
                "GetExternalIPAddress", Map.of("NewExternalIPAddress", ""),
                "X_AVM_DE_GetDsliteStatus", Map.of("NewX_AVM_DE_DsliteStatus", "1"),
                "X_AVM_DE_GetExternalIPv6Address", Map.of("NewExternalIPv6Address", "2001:db8:1::1", "NewPrefixLength", "64"),
                "X_AVM_DE_GetIPv6Prefix", Map.of("NewIPv6Prefix", "2001:db8:2::", "NewPrefixLength", "56"));
        given().queryParam("host", "localhost").get("/api/router").then().statusCode(200)
                .body("ipv4Kind", equalTo("DS_LITE"))
                .body("dsLite", equalTo(true))
                .body("externalIPv4", nullValue())
                .body("externalIPv6", equalTo("2001:db8:1::1"))
                .body("ipv6Prefix", equalTo("2001:db8:2::"))
                .body("ipv6PrefixLength", equalTo(56));
    }

    /** Another router: only the standard actions, no second description. */
    @Test
    void plainGatewayWithAPublicAddress() {
        FakeIgdResource.secondDescription = false;
        FakeIgdResource.answers = Map.of("GetStatusInfo", CONNECTED, "GetExternalIPAddress", Map.of("NewExternalIPAddress", "203.0.113.7"));
        given().queryParam("host", "localhost").get("/api/router").then().statusCode(200)
                .body("ipv4Kind", equalTo("PUBLIC"))
                .body("dsLite", equalTo(false))
                .body("accessType", nullValue())
                .body("portMappingAllowed", nullValue())
                .body("ipv6Firewall", equalTo(false));
    }

    @Test
    void routerWithoutUpnpStatus() {
        given().queryParam("host", "localhost").get("/api/router").then().statusCode(502).body("code", equalTo("router_no_upnp"));
    }

    @Test
    void onlyAHostNameIsTaken() {
        given().queryParam("host", "localhost/x?y").get("/api/router").then().statusCode(502).body("code", equalTo("router_unreachable"));
        given().queryParam("host", "nothing.invalid").get("/api/router").then().statusCode(502).body("code", equalTo("router_unreachable"));
    }
}
