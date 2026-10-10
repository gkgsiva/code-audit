package com.acme.orders.client;

import java.net.URL;

import javax.xml.namespace.QName;

import org.apache.axis.client.Call;
import org.apache.axis.client.Service;

/**
 * Axis 1 client for the central pricing web service.
 */
public class PricingSoapClient {

    private static final String ENDPOINT = "http://pricing.acme.local:8080/axis/services/PricingService";

    public double quote(long orderId, double baseAmount) throws Exception {
        Service service = new Service();
        Call call = (Call) service.createCall();
        call.setTargetEndpointAddress(new URL(ENDPOINT));
        call.setOperationName(new QName("http://pricing.acme.com", "quote"));
        Object result = call.invoke(new Object[] {new Long(orderId), new Double(baseAmount)});
        return ((Double) result).doubleValue();
    }
}
