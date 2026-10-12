package com.acme.orders;

import java.sql.Connection;
import javax.naming.InitialContext;
import javax.sql.DataSource;

public class ConnectionFactory {
    public static Connection get() throws Exception {
        DataSource ds = (DataSource) new InitialContext().lookup("java:comp/env/jdbc/orders");
        return ds.getConnection();
    }
}
