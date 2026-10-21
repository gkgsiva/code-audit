package com.acme.orders.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** Card payments; the address comes from payment.url in application.properties. */
@FeignClient(name = "payment-service", url = "${payment.url}")
public interface PaymentClient {

    @PostMapping("/charges")
    String charge(@RequestBody String charge);

    @GetMapping("/charges/{id}")
    String get(@PathVariable("id") String id);
}
