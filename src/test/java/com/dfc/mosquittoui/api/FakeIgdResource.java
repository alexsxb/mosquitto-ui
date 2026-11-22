package com.dfc.mosquittoui.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/** Stands in for the UPnP status part of a FRITZ!Box, with what the 6860 5G of a mobile connection answered, or other values. */
@Path("/fake-igd")
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.TEXT_XML)
public class FakeIgdResource {

    /** Output arguments by action; an action that is missing gets a SOAP fault, as on a router that does not know it. */
    static volatile Map<String, Map<String, String>> answers = Map.of();
    static volatile boolean secondDescription = true;

    /** Version 1 names WANIPConnection a first time with a control URL away from the router, which must not be taken. */
    private static String description(String version) {
        return """
                <?xml version="1.0"?>
                <root xmlns="urn:schemas-upnp-org:device-1-0"><device><modelName>FRITZ!Box 6860 5G</modelName><deviceList>
                <device><serviceList><service><serviceType>urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1</serviceType>
                <controlURL>/igdupnp/control/WANCommonIFC1</controlURL></service></serviceList><deviceList><device><serviceList>
                %2$s
                <service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:%1$s</serviceType>
                <controlURL>/igdupnp/control/WANIPConn1</controlURL></service>
                </serviceList></device></deviceList></device></deviceList></device></root>
                """.formatted(version, version.equals("2")
                ? "<service><serviceType>urn:schemas-upnp-org:service:WANIPv6FirewallControl:1</serviceType><controlURL>/igd2upnp/control/WANIPv6Firewall1</controlURL></service>"
                : "<service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType><controlURL>https://elsewhere.test/x</controlURL></service>");
    }

    @GET
    @Path("/igddesc.xml")
    public String first() {
        return description("1");
    }

    @GET
    @Path("/igd2desc.xml")
    public String second() {
        if (!secondDescription) {
            throw new NotFoundException();
        }
        return description("2");
    }

    @POST
    @Path("/{kind}/control/{service}")
    public String control(@PathParam("service") String service, @HeaderParam("SOAPAction") String soapAction) {
        String action = soapAction.replace("\"", "");
        String type = action.substring(0, action.indexOf('#'));
        String name = action.substring(action.indexOf('#') + 1);
        Map<String, String> values = answers.get(name);
        if (values == null) {
            return "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><faultstring>UPnPError</faultstring></s:Fault></s:Body></s:Envelope>";
        }
        StringBuilder body = new StringBuilder();
        values.forEach((key, value) -> body.append('<').append(key).append('>').append(value).append("</").append(key).append('>'));
        return "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:" + name + "Response xmlns:u=\"" + type + "\">"
                + body + "</u:" + name + "Response></s:Body></s:Envelope>";
    }
}
