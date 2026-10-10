package com.acme.billing.batch;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;

import org.springframework.beans.factory.annotation.Autowired;

import com.acme.billing.common.mapper.PaymentMapper;

/** Receives payment notifications from the bank (queue PAYMENTS.IN, see invoice-job.xml). */
public class PaymentListener implements MessageListener {

    @Autowired
    private PaymentMapper payments;

    @Override
    public void onMessage(Message message) {
        try {
            String body = ((TextMessage) message).getText();
            payments.settle(Long.parseLong(body.trim()));
        } catch (JMSException e) {
            throw new IllegalStateException(e);
        }
    }
}
