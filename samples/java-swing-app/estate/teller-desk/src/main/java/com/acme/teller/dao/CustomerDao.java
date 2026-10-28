package com.acme.teller.dao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class CustomerDao {
    private static final String URL = "jdbc:oracle:thin:@tellerdb.acme.local:1521/TELLER";

    public List<String[]> find(String name, String branch) {
        String sql = "SELECT CUST_ID, CUST_NAME FROM CUSTOMER WHERE CUST_NAME LIKE '" + name + "%'";
        if (!"ALL".equals(branch)) {
            sql += " AND BRANCH = '" + branch + "'";
        }
        List<String[]> rows = new ArrayList<>();
        try (Connection cn = DriverManager.getConnection(URL, "teller_app", System.getenv("TELLER_PWD"));
             Statement st = cn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new String[] {rs.getString(1), rs.getString(2)});
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }
}
