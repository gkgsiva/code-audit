package com.acme.orders;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class OrderDao {
    public List<String> openOrders(String customer) throws Exception {
        List<String> out = new ArrayList<String>();
        Connection con = ConnectionFactory.get();
        PreparedStatement ps = con.prepareStatement(
            "SELECT order_id FROM orders WHERE customer_id = ? AND status = 'OPEN'");
        ps.setString(1, customer);
        ResultSet rs = ps.executeQuery();
        while (rs.next()) {
            out.add(rs.getString(1));
        }
        return out;
    }
}
