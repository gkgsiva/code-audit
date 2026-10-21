package com.acme.orders.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    @GetMapping("/{id}")
    public String get(@PathVariable long id) {
        return "{}";
    }

    @PutMapping("/{id}")
    public String update(@PathVariable long id, @RequestBody String body) {
        return body;
    }
}
