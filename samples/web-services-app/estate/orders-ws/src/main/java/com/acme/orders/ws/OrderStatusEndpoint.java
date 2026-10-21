package com.acme.orders.ws;

import javax.jws.WebMethod;
import javax.jws.WebService;

/**
 * SOAP service the branch systems call to follow an order.
 */
@WebService(serviceName = "OrderStatusService", targetNamespace = "http://orders.acme.com/status")
public class OrderStatusEndpoint {

    @WebMethod
    public String getStatus(long orderId) {
        return "SHIPPED";
    }

    @WebMethod
    public boolean cancelOrder(long orderId, String reason) {
        return true;
    }

    @WebMethod(exclude = true)
    public void reload() {
    }
}
