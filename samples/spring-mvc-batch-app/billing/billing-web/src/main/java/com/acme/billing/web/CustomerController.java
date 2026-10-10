package com.acme.billing.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.acme.billing.common.dao.CustomerRepository;

@Controller
public class CustomerController {

    @Autowired
    private CustomerRepository customers;

    @GetMapping("/customers/search")
    public String search(@RequestParam(value = "segment", required = false) String segment,
                         Model model) {
        if (segment != null) {
            model.addAttribute("customers", customers.findBySegment(segment));
        }
        return "customers/search";
    }
}
