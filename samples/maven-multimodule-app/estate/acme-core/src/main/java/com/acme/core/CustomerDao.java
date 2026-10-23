package com.acme.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Reads customers for the web and the batches. */
public class CustomerDao {
    private final Connection conn;

    public CustomerDao(Connection conn) {
        this.conn = conn;
    }

    public PreparedStatement byId(long id) throws SQLException {
        PreparedStatement ps = conn.prepareStatement("SELECT ID, NAME FROM CUSTOMER WHERE ID = ?");
        ps.setLong(1, id);
        return ps;
    }
}
