package com.acme.billing.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;

import com.acme.billing.common.dao.InvoiceDao;

@Controller
@RequestMapping("/invoices")
public class InvoiceController {

    @Autowired
    private InvoiceDao invoiceDao;

    @Autowired
    private InvoiceEventPublisher publisher;

    @RequestMapping(method = RequestMethod.GET)
    public String list(Model model) {
        model.addAttribute("invoices", invoiceDao.openInvoices(50));
        return "invoices/list";
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.GET)
    public ModelAndView detail(@PathVariable("id") long id) {
        return new ModelAndView("invoices/detail", "id", id);
    }

    @RequestMapping(value = "/{id}/pay", method = RequestMethod.POST)
    public String pay(@PathVariable("id") long id) {
        invoiceDao.markPaid(id);
        publisher.invoicePaid(id);
        return "redirect:/invoices";
    }
}
