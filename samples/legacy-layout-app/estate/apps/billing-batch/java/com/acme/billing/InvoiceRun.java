package com.acme.billing;

import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Properties;

/** Monthly invoicing: turns the month's usage into invoices. */
public class InvoiceRun {
    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        p.load(new FileInputStream(args[0]));
        Connection con = DriverManager.getConnection(p.getProperty("db.url"));
        PreparedStatement ps = con.prepareStatement(
            "INSERT INTO invoices (customer_id, amount) "
            + "SELECT customer_id, SUM(amount) FROM usage_records GROUP BY customer_id");
        ps.executeUpdate();
        con.commit();
    }
}
