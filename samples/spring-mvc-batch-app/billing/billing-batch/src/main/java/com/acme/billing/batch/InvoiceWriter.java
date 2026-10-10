package com.acme.billing.batch;

import java.util.List;
import java.util.Map;

import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Writes generated invoices and their audit trail. */
public class InvoiceWriter implements ItemWriter<Map<String, Object>> {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void write(List<? extends Map<String, Object>> items) {
        for (Map<String, Object> item : items) {
            jdbcTemplate.update(
                    "INSERT INTO invoices (invoice_id, customer_id, amount, status, due_date) "
                    + "VALUES (invoices_seq.NEXTVAL, ?, ?, 'OPEN', ADD_MONTHS(SYSDATE, 1))",
                    item.get("CUSTOMER_ID"), item.get("AMOUNT"));
            jdbcTemplate.update("INSERT INTO invoice_audit (customer_id, created_on) VALUES (?, SYSDATE)",
                    item.get("CUSTOMER_ID"));
        }
    }
}
