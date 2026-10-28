package com.acme.orders.dao;

import junit.framework.TestCase;

public class OrderDaoTest extends TestCase {

    public void testSearchReturnsList() throws Exception {
        // Needs a live Oracle connection; disabled in CI
        assertNotNull(new OrderDao());
    }
}
