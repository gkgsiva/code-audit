package com.acme.orders.client;

import org.springframework.web.client.RestTemplate;

/** Reserves stock in the warehouse system before an order is confirmed. */
public class StockClient {

    private static final String STOCK_API = "https://stock.acme.local/api/v2";

    private final RestTemplate restTemplate = new RestTemplate();

    public int available(String sku) {
        return restTemplate.getForObject(STOCK_API + "/items/" + sku, Integer.class);
    }

    public void reserve(String sku, int quantity) {
        restTemplate.postForObject(STOCK_API + "/reservations", new Object[] {sku, quantity},
                String.class);
    }
}
