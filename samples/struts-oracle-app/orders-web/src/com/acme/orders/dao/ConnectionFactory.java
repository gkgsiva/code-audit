package com.acme.orders.dao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Hands out JDBC connections. The URL points at the production Oracle host.
 */
public final class ConnectionFactory {

    private static final String URL = "jdbc:oracle:thin:@ordersdb01.acme.local:1521:ORDP";

    static {
        try {
            Class.forName("oracle.jdbc.driver.OracleDriver");
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ConnectionFactory() {
    }

    public static Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.put("user", "orders_app");
        props.put("password", System.getProperty("orders.db.password"));
        return DriverManager.getConnection(URL, props);
    }
}
