package com.dfc.mosquittoui.router;

import com.dfc.mosquittoui.api.Dtos.RouterInfo;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Asks the router in the LAN through UPnP IGD what kind of internet connection it has: the status part, which needs no login.
 * Any IGD gives the IPv4 address; a FRITZ!Box also tells DS-Lite and IPv6, other routers leave those empty. Nothing is changed on
 * the router.
 */
@ApplicationScoped
public class RouterProbe {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** A name or an address, never a URL: the request goes to a fixed port and fixed paths. */
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}|\\[[0-9A-Fa-f:.]+\\]");
    private static final String COMMON = "urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1";
    private static final String IP_CONNECTION = "urn:schemas-upnp-org:service:WANIPConnection:";
    private static final String IPV6_FIREWALL = "urn:schemas-upnp-org:service:WANIPv6FirewallControl:1";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();

    /** Where the descriptions are, with %s for the host; port 49000 is that of the FRITZ!Box. */
    @ConfigProperty(name = "mosquitto-ui.router.url", defaultValue = "http://%s:49000")
    String urlTemplate;

    public RouterInfo probe(String host) {
        if (host == null || !HOST.matcher(host.strip()).matches()) {
            throw new RouterException("router_unreachable", "Not a host name or address: " + host);
        }
        String base = urlTemplate.formatted(host.strip());
        Document description = description(base + "/igddesc.xml");
        if (description == null) {
            throw new RouterException("router_no_upnp", "The router gives no UPnP description at " + base);
        }
        Map<String, String> controls = controls(description);
        // the second description has version 2 of the services, and with it the IPv6 firewall
        Document second = description(base + "/igd2desc.xml");
        if (second != null) {
            controls.putAll(controls(second));
        }
        String connection = controls.containsKey(IP_CONNECTION + "2") ? IP_CONNECTION + "2" : IP_CONNECTION + "1";
        if (!controls.containsKey(connection)) {
            throw new RouterException("router_no_upnp", "The router names no WANIPConnection service");
        }
        Map<String, String> status = call(base, controls, connection, "GetStatusInfo");
        Map<String, String> external = call(base, controls, connection, "GetExternalIPAddress");
        Map<String, String> dsLite = call(base, controls, COMMON, "X_AVM_DE_GetDsliteStatus");
        Map<String, String> addon = call(base, controls, COMMON, "GetAddonInfos");
        Map<String, String> link = call(base, controls, COMMON, "GetCommonLinkProperties");
        Map<String, String> ipv6 = call(base, controls, connection, "X_AVM_DE_GetExternalIPv6Address");
        Map<String, String> prefix = call(base, controls, connection, "X_AVM_DE_GetIPv6Prefix");
        if (status == null && external == null) {
            throw new RouterException("router_no_upnp", "The router does not answer UPnP requests; status information over UPnP may be off");
        }

        String ipv4 = value(external, "NewExternalIPAddress");
        boolean lite = "1".equals(value(dsLite, "NewX_AVM_DE_DsliteStatus"));
        String ipv6Address = blankToNull(value(ipv6, "NewExternalIPv6Address"));
        String ipv6Prefix = value(prefix, "NewIPv6Prefix");
        Integer prefixLength = number(value(prefix, "NewPrefixLength"));
        if ("::".equals(ipv6Prefix) || prefixLength == null || prefixLength == 0) {
            ipv6Prefix = null;
            prefixLength = null;
        }
        String accessType = blankToNull(value(addon, "NewX_AVM_DE_WANAccessType"));
        String upnpControl = value(addon, "NewUpnpControlEnabled");
        return new RouterInfo(host.strip(), modelName(description), accessType != null ? accessType : blankToNull(value(link, "NewWANAccessType")),
                blankToNull(value(status, "NewConnectionStatus")), blankToNull(ipv4), AddressKind.of(ipv4, lite).name(), lite, ipv6Address, ipv6Prefix,
                prefixLength, upnpControl == null ? null : upnpControl.equals("1"), controls.containsKey(IPV6_FIREWALL));
    }

    private static String value(Map<String, String> values, String name) {
        return values == null ? null : values.get(name);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Integer number(String value) {
        try {
            return value == null ? null : Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static DocumentBuilder parser() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Document parse(String xml) {
        try {
            return parser().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    /** @return null when there is none, as with a router without UPnP */
    private Document description(String url) {
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new RouterException("router_unreachable", "The router does not answer at " + url + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RouterException("router_unreachable", "Interrupted");
        }
        return response.statusCode() == 200 ? parse(response.body()) : null;
    }

    private static String modelName(Document description) {
        NodeList names = description.getElementsByTagNameNS("*", "modelName");
        return names.getLength() == 0 ? null : blankToNull(names.item(0).getTextContent());
    }

    /** Service type to control path; only paths on the router itself count. */
    private static Map<String, String> controls(Document description) {
        Map<String, String> controls = new HashMap<>();
        NodeList services = description.getElementsByTagNameNS("*", "service");
        for (int i = 0; i < services.getLength(); i++) {
            Element service = (Element) services.item(i);
            String type = child(service, "serviceType");
            String control = child(service, "controlURL");
            if (type != null && control != null && control.startsWith("/") && !control.startsWith("//")) {
                controls.putIfAbsent(type, control);
            }
        }
        return controls;
    }

    private static String child(Element element, String name) {
        NodeList children = element.getElementsByTagNameNS("*", name);
        return children.getLength() == 0 ? null : blankToNull(children.item(0).getTextContent());
    }

    /** @return the output arguments, or null when the router does not know the service or the action */
    private Map<String, String> call(String base, Map<String, String> controls, String service, String action) {
        String control = controls.get(service);
        if (control == null) {
            return null;
        }
        String body = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body><u:%1$s xmlns:u="%2$s"></u:%1$s></s:Body></s:Envelope>""".formatted(action, service);
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + control)).timeout(TIMEOUT)
                    .header("Content-Type", "text/xml; charset=\"utf-8\"").header("SOAPAction", "\"" + service + "#" + action + "\"")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            Document document = response.statusCode() == 200 ? parse(response.body()) : null;
            if (document == null) {
                return null;
            }
            NodeList answers = document.getElementsByTagNameNS("*", action + "Response");
            if (answers.getLength() == 0) {
                return null;
            }
            Map<String, String> values = new HashMap<>();
            for (Node node = answers.item(0).getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element) {
                    values.put(element.getLocalName() != null ? element.getLocalName() : element.getTagName(), element.getTextContent());
                }
            }
            return values;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
