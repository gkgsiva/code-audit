package com.acme.billing.common.mapper;

import java.util.List;
import java.util.Map;

/** MyBatis mapper; statements live in mappers/PaymentMapper.xml. */
public interface PaymentMapper {

    List<Map<String, Object>> findPayments(Map<String, Object> filter);

    int insertPayment(Map<String, Object> payment);

    int settle(long paymentId);
}
