package com.acme.orders.service;

import java.util.List;
import java.util.Map;

import com.acme.orders.client.PricingSoapClient;
import com.acme.orders.dao.OrderDao;

public class OrderService {

    private final OrderDao dao = new OrderDao();
    private final PricingSoapClient pricing = new PricingSoapClient();

    public boolean authenticate(String user, String password) throws Exception {
        return dao.userExists(user, password);
    }

    public List searchOrders(String customer, String status) throws Exception {
        return dao.search(customer, status);
    }

    public Map getOrder(long orderId) throws Exception {
        return dao.find(orderId);
    }

    public double priceOrder(long orderId) throws Exception {
        double local = dao.totalWithTax(orderId);
        return pricing.quote(orderId, local);
    }
}
