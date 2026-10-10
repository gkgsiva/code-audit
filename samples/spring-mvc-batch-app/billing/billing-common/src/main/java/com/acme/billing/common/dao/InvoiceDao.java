package com.acme.billing.common.dao;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Invoice queries with JdbcTemplate (Oracle syntax on purpose). */
@Repository
public class InvoiceDao {

    private static final String OPEN_INVOICES =
            "SELECT invoice_id, customer_id, amount, NVL(status, 'OPEN') AS status "
            + "FROM invoices WHERE status = 'OPEN' AND ROWNUM <= ?";

    private static final String MARK_PAID =
            "UPDATE invoices SET status = 'PAID', paid_on = SYSDATE WHERE invoice_id = ?";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> openInvoices(int max) {
        return jdbcTemplate.queryForList(OPEN_INVOICES, max);
    }

    public int markPaid(long invoiceId) {
        return jdbcTemplate.update(MARK_PAID, invoiceId);
    }

    public int countOverdue() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoices i, customers c "
                + "WHERE i.customer_id = c.id(+) AND i.due_date < SYSDATE", Integer.class);
    }

    public List<Map<String, Object>> search(String status, String orderBy) {
        // Dynamic ORDER BY: built at run time.
        String sql = "SELECT invoice_id, amount FROM invoices WHERE status = ? ORDER BY " + orderBy;
        return jdbcTemplate.queryForList(sql, status);
    }
}
