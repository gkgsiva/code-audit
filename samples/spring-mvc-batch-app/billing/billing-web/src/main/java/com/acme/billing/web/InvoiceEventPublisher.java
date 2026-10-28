package com.acme.billing.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

/** Sends "invoice paid" events to IBM MQ through the JmsTemplate defined in jms.xml. */
@Component
public class InvoiceEventPublisher {

    @Autowired
    private JmsTemplate jmsTemplate;

    public void invoicePaid(long invoiceId) {
        jmsTemplate.convertAndSend("BILLING.INVOICE.PAID", String.valueOf(invoiceId));
    }
}
