"""Web services (REST and SOAP): services offered (Java annotations, WSDL, Axis, CXF) and called
(REST clients, SOAP stubs and dynamic calls) in Java, XML, TypeScript, C and COBOL."""

from __future__ import annotations

from legacy_audit.analyzers.java import JavaAnalyzer
from legacy_audit.analyzers.websvc import WebServicesAnalyzer, split_url
from tests.analyzers.helpers import entities, run

REST_CONTROLLER = """package com.acme.orders.api;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderApi {
    @GetMapping("/{id}")
    public Order get(@PathVariable long id) { return null; }

    @PostMapping
    public Order create(@RequestBody Order o) { return o; }
}

@Controller
class OrderPages {
    @GetMapping("/orders")
    public String list() { return "orders/list"; }

    @GetMapping("/orders/count")
    @ResponseBody
    public long count() { return 1; }
}
"""


def _ops(facts, side="called"):
    return sorted(
        (w.name, w.style, w.frameworks[0], o.name)
        for w in entities(facts, "web_services")
        if w.side == side
        for o in w.operations or [None]
        if o is not None
    )


def test_spring_rest_controllers_are_rest_web_services_pages_are_not() -> None:
    facts = run(JavaAnalyzer(), "src/com/acme/orders/api/OrderApi.java", REST_CONTROLLER)
    svc = {s.name: s for s in entities(facts, "services")}
    assert svc["GET /api/orders/{id}"].protocol == "REST"
    assert svc["GET /api/orders/{id}"].framework == "Spring MVC"
    assert svc["GET /api/orders/{id}"].web_service == "OrderApi"
    assert svc["POST /api/orders"].protocol == "REST"
    assert svc["GET /orders"].protocol is None  # renders a view: a page, not a web service
    assert svc["GET /orders/count"].protocol == "REST"  # @ResponseBody


JAX = """package com.acme.ws;

import javax.jws.WebMethod;
import javax.jws.WebService;
import javax.ws.rs.GET;
import javax.ws.rs.Path;

@WebService(serviceName = "CustomerService")
public class CustomerEndpoint {
    @WebMethod
    public Customer find(String id) { return null; }

    @WebMethod(exclude = true)
    public void internal() { }
}

@Path("/customers")
class CustomerResource {
    @GET
    @Path("/{id}")
    public Customer get(String id) { return null; }
}
"""


def test_jax_ws_and_jax_rs() -> None:
    facts = run(JavaAnalyzer(), "src/com/acme/ws/CustomerEndpoint.java", JAX)
    svc = {s.name: s for s in entities(facts, "services")}
    assert svc["CustomerEndpoint.find"].protocol == "SOAP"
    assert svc["CustomerEndpoint.find"].web_service == "CustomerService"
    assert "CustomerEndpoint.internal" not in svc
    assert svc["GET /customers/{id}"].framework == "JAX-RS"


SPRING_WS = """package com.acme.ws;

import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;

@Endpoint
public class QuoteEndpoint {
    @PayloadRoot(namespace = "http://acme.com/quotes", localPart = "GetQuoteRequest")
    public Object quote(Object request) { return null; }
}
"""


def test_spring_ws_endpoint() -> None:
    facts = run(JavaAnalyzer(), "src/com/acme/ws/QuoteEndpoint.java", SPRING_WS)
    (s,) = entities(facts, "services")
    assert (s.name, s.kind, s.protocol, s.framework) == (
        "QuoteEndpoint.GetQuoteRequest", "soap-operation", "SOAP", "Spring-WS",
    )  # fmt: skip


JWS = """public class Calculator {
    public int add(int a, int b) { return a + b; }
    private int secret() { return 0; }
}
"""


def test_axis_jws_file() -> None:
    facts = run(JavaAnalyzer(), "webapp/Calculator.jws", JWS)
    (s,) = entities(facts, "services")
    assert (s.name, s.framework, s.url) == ("Calculator.add", "Apache Axis (JWS)", "/Calculator.jws")


CLIENTS = """package com.acme.orders.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import javax.xml.namespace.QName;
import org.apache.axis.client.Call;
import org.apache.axis.client.Service;
import org.apache.http.client.methods.HttpPost;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

public class PartnerClient {
    private static final String STOCK = "https://stock.acme.local/api/v2";
    private final RestTemplate restTemplate = new RestTemplate();

    public void calls(String id) throws Exception {
        restTemplate.getForObject(STOCK + "/items/" + id, String.class);
        restTemplate.exchange("https://stock.acme.local/api/v2/reservations", HttpMethod.POST,
                null, String.class);
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://geo.acme.local/geocode"))
                .GET().build();
        HttpPost post = new HttpPost("https://mail.acme.local/send");
        Call call = (Call) new Service().createCall();
        call.setTargetEndpointAddress(new java.net.URL("http://pricing.acme.local/axis/services/PricingService"));
        call.setOperationName(new QName("http://pricing.acme.com", "quote"));
    }
}
"""


def test_java_rest_and_soap_clients() -> None:
    facts = run(JavaAnalyzer(), "src/com/acme/orders/client/PartnerClient.java", CLIENTS)
    ops = _ops(facts)
    assert ("PartnerClient", "REST", "RestTemplate", "GET /api/v2/items/?") in ops
    assert ("PartnerClient", "REST", "RestTemplate", "POST /api/v2/reservations") in ops
    assert ("PartnerClient", "REST", "java.net.http.HttpClient", "GET /geocode") in ops
    assert ("PartnerClient", "REST", "Apache HttpClient", "POST /send") in ops
    assert ("PricingService", "SOAP", "Apache Axis", "quote") in ops
    urls = {w.url for w in entities(facts, "web_services")}
    assert "http://pricing.acme.local/axis/services/PricingService" in urls
    # The QName namespace is not an endpoint
    targets = {i.target for i in entities(facts, "integrations")}
    assert "http://pricing.acme.com" not in targets


FEIGN = """package com.acme.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "payment-service", url = "${payment.url}")
public interface PaymentClient {
    @PostMapping("/charges")
    Charge charge(@RequestBody Charge c);

    @GetMapping("/charges/{id}")
    Charge get(@PathVariable String id);
}
"""


def test_feign_client_is_called_not_offered() -> None:
    facts = run(JavaAnalyzer(), "src/com/acme/client/PaymentClient.java", FEIGN)
    assert not entities(facts, "services")
    assert _ops(facts) == [
        ("payment-service", "REST", "Feign", "GET /charges/{id}"),
        ("payment-service", "REST", "Feign", "POST /charges"),
    ]


GENERATED = """package com.acme.erp;

import javax.xml.ws.Service;
import javax.xml.ws.WebServiceClient;

@WebServiceClient(name = "InvoiceService", targetNamespace = "http://erp.acme.com/invoice",
        wsdlLocation = "http://erp.acme.local/ws/InvoiceService?wsdl")
public class InvoiceService extends Service {
}
"""

USER = """package com.acme.billing;

import javax.xml.ws.BindingProvider;

public class Billing {
    void send() {
        InvoicePort port = new InvoiceService().getInvoiceServicePort();
        port.submit(null);
    }
}
"""


def test_jax_ws_generated_client_and_its_use() -> None:
    gen = run(JavaAnalyzer(), "src/com/acme/erp/InvoiceService.java", GENERATED)
    (w,) = entities(gen, "web_services")
    assert (w.name, w.style, w.url, w.namespace) == (
        "InvoiceService", "SOAP", "http://erp.acme.local/ws/InvoiceService",
        "http://erp.acme.com/invoice",
    )  # fmt: skip
    use = run(JavaAnalyzer(), "src/com/acme/billing/Billing.java", USER)
    (u,) = entities(use, "web_services")
    assert (u.name, u.style, u.callers) == ("InvoiceService", "SOAP", [
        "src/com/acme/billing/Billing.java"])  # fmt: skip


WSDL = """<?xml version="1.0"?>
<definitions name="Pricing" targetNamespace="http://pricing.acme.com"
    xmlns="http://schemas.xmlsoap.org/wsdl/" xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/">
  <portType name="PricingPortType">
    <operation name="quote"/>
    <operation name="listPrices"/>
  </portType>
  <binding name="PricingBinding" type="tns:PricingPortType">
    <soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
  </binding>
  <service name="PricingService">
    <port name="PricingPort" binding="tns:PricingBinding">
      <soap:address location="http://pricing.acme.local/axis/services/PricingService"/>
    </port>
  </service>
</definitions>
"""


def test_wsdl_contract() -> None:
    facts = run(WebServicesAnalyzer(), "orders-web/wsdl/Pricing.wsdl", WSDL)
    (w,) = entities(facts, "web_services")
    assert (w.name, w.side, w.style, w.namespace) == (
        "PricingService", "contract", "SOAP", "http://pricing.acme.com",
    )  # fmt: skip
    assert w.url == "http://pricing.acme.local/axis/services/PricingService"
    assert [o.name for o in w.operations] == ["quote", "listPrices"]
    assert [o.line for o in w.operations] == [5, 6]


WSDD = """<deployment xmlns="http://xml.apache.org/axis/wsdd/"
    xmlns:java="http://xml.apache.org/axis/wsdd/providers/java">
  <service name="OrderStatus" provider="java:RPC">
    <parameter name="className" value="com.acme.ws.OrderStatusService"/>
    <parameter name="allowedMethods" value="getStatus cancel"/>
  </service>
  <service name="Version" provider="java:RPC">
    <parameter name="className" value="org.apache.axis.Version"/>
  </service>
</deployment>
"""

AXIS2 = """<service name="StockQuote">
  <parameter name="ServiceClass">com.acme.ws.StockQuote</parameter>
  <operation name="getPrice"/>
</service>
"""


def test_axis_deployment_descriptors() -> None:
    facts = run(WebServicesAnalyzer(), "WEB-INF/server-config.wsdd", WSDD)
    names = sorted((s.name, s.framework, s.url) for s in entities(facts, "services"))
    assert names == [
        ("OrderStatus.cancel", "Apache Axis", "/services/OrderStatus"),
        ("OrderStatus.getStatus", "Apache Axis", "/services/OrderStatus"),
        ("Version.*", "Apache Axis", "/services/Version"),
    ]
    facts = run(WebServicesAnalyzer(), "WEB-INF/services/stock/META-INF/services.xml", AXIS2)
    (s,) = entities(facts, "services")
    assert (s.name, s.handler, s.framework) == (
        "StockQuote.getPrice", "com.acme.ws.StockQuote.getPrice", "Apache Axis2",
    )  # fmt: skip


CXF = """<beans xmlns="http://www.springframework.org/schema/beans"
    xmlns:jaxws="http://cxf.apache.org/jaxws" xmlns:jaxrs="http://cxf.apache.org/jaxrs">
  <bean id="customerImpl" class="com.acme.ws.CustomerEndpoint"/>
  <jaxws:endpoint id="customer" implementor="#customerImpl" address="/CustomerService"/>
  <jaxrs:server id="rest" address="/rest">
    <jaxrs:serviceBeans><bean class="com.acme.ws.CustomerResource"/></jaxrs:serviceBeans>
  </jaxrs:server>
  <jaxws:client id="crm" serviceClass="com.acme.crm.CrmPort"
      address="https://crm.acme.local/services/Crm"/>
</beans>
"""

WEB_XML = """<web-app>
  <servlet><servlet-name>cxf</servlet-name>
    <servlet-class>org.apache.cxf.transport.servlet.CXFServlet</servlet-class></servlet>
  <servlet-mapping><servlet-name>cxf</servlet-name><url-pattern>/ws/*</url-pattern></servlet-mapping>
</web-app>
"""


def test_cxf_endpoints_clients_and_servlets() -> None:
    facts = run(WebServicesAnalyzer(), "WEB-INF/cxf-beans.xml", CXF)
    eps = sorted((s.name, s.data["url"]) for s in facts.symbols if s.kind == "ws-endpoint")
    assert eps == [
        ("com.acme.ws.CustomerEndpoint", "/CustomerService"),
        ("com.acme.ws.CustomerResource", "/rest"),
    ]
    assert _ops(facts) == []
    (client,) = entities(facts, "web_services")
    assert (client.name, client.url, client.frameworks) == (
        "CrmPort", "https://crm.acme.local/services/Crm", ["Apache CXF"],
    )  # fmt: skip
    facts = run(WebServicesAnalyzer(), "WEB-INF/web.xml", WEB_XML)
    (sv,) = [s for s in facts.symbols if s.kind == "ws-servlet"]
    assert (sv.name, sv.data["url"]) == ("Apache CXF", "/ws")


def test_split_url() -> None:
    assert split_url("https://User:pw@Stock.acme.local:8443/api/v2/?x=1") == (
        "https://stock.acme.local:8443", "/api/v2",
    )  # fmt: skip
    assert split_url("http://?/orders") == (None, "/orders")
    assert split_url("/api/orders/?") == (None, "/api/orders/?")
    assert split_url("http://erp.acme.local/ws/Invoice?wsdl") == (
        "http://erp.acme.local", "/ws/Invoice",
    )  # fmt: skip
