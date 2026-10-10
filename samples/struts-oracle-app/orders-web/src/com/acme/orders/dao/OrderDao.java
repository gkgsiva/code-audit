package com.acme.orders.dao;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data access for orders. All SQL is Oracle-specific.
 */
public class OrderDao {

    private static final String FIND_USER =
        "SELECT COUNT(*) FROM USERS WHERE USERNAME = ? AND PASSWORD = ?";

    private static final String SEARCH_ORDERS =
        "SELECT * FROM (SELECT O.ORDER_ID, C.NAME, NVL(O.STATUS, 'NEW') STATUS, O.CREATED_AT "
        + "FROM ORDERS O, CUSTOMERS C WHERE O.CUSTOMER_ID = C.CUSTOMER_ID(+) "
        + "AND UPPER(C.NAME) LIKE UPPER(?) ORDER BY O.CREATED_AT DESC) WHERE ROWNUM <= 50";

    private static final String GET_ORDER =
        "SELECT O.ORDER_ID, O.STATUS, DECODE(O.PRIORITY, 1, 'HIGH', 2, 'MEDIUM', 'LOW') PRIO "
        + "FROM ORDERS O WHERE O.ORDER_ID = ?";

    private static final String ORDER_TREE =
        "SELECT ORDER_ID, PARENT_ORDER_ID, LEVEL FROM ORDERS "
        + "START WITH ORDER_ID = ? CONNECT BY PRIOR ORDER_ID = PARENT_ORDER_ID";

    public boolean userExists(String user, String password) throws SQLException {
        Connection con = ConnectionFactory.getConnection();
        try {
            PreparedStatement ps = con.prepareStatement(FIND_USER);
            ps.setString(1, user);
            ps.setString(2, password);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } finally {
            con.close();
        }
    }

    public List search(String customer, String status) throws SQLException {
        Connection con = ConnectionFactory.getConnection();
        List result = new ArrayList();
        try {
            PreparedStatement ps = con.prepareStatement(SEARCH_ORDERS);
            ps.setString(1, "%" + customer + "%");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map row = new HashMap();
                row.put("id", new Long(rs.getLong(1)));
                row.put("customer", rs.getString(2));
                row.put("status", rs.getString(3));
                result.add(row);
            }
        } finally {
            con.close();
        }
        if (status != null && status.length() > 0) {
            // Dynamic filter appended at runtime
            Statement st = ConnectionFactory.getConnection().createStatement();
            st.executeQuery("SELECT ORDER_ID FROM ORDERS WHERE STATUS = '" + status + "'");
        }
        return result;
    }

    public Map find(long orderId) throws SQLException {
        Connection con = ConnectionFactory.getConnection();
        try {
            PreparedStatement ps = con.prepareStatement(GET_ORDER);
            ps.setLong(1, orderId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                return null;
            }
            Map row = new HashMap();
            row.put("id", new Long(rs.getLong(1)));
            row.put("status", rs.getString(2));
            row.put("priority", rs.getString(3));
            return row;
        } finally {
            con.close();
        }
    }

    public List orderTree(long orderId) throws SQLException {
        Connection con = ConnectionFactory.getConnection();
        List ids = new ArrayList();
        try {
            PreparedStatement ps = con.prepareStatement(ORDER_TREE);
            ps.setLong(1, orderId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                ids.add(new Long(rs.getLong(1)));
            }
        } finally {
            con.close();
        }
        return ids;
    }

    public double totalWithTax(long orderId) throws SQLException {
        Connection con = ConnectionFactory.getConnection();
        try {
            CallableStatement cs = con.prepareCall("{? = call PKG_ORDER_PRICING.TOTAL_WITH_TAX(?)}");
            cs.registerOutParameter(1, Types.NUMERIC);
            cs.setLong(2, orderId);
            cs.execute();
            return cs.getDouble(1);
        } finally {
            con.close();
        }
    }
}
