package com.acme.billing.common.dao;

import java.util.List;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.acme.billing.common.model.Customer;

/** Customer lookups with Hibernate HQL. */
@Repository
public class CustomerRepository {

    @Autowired
    private SessionFactory sessionFactory;

    @SuppressWarnings("unchecked")
    public List<Customer> findBySegment(String segment) {
        Session session = sessionFactory.getCurrentSession();
        return session.createQuery("from Customer c where c.segment = :segment order by c.name")
                .setParameter("segment", segment)
                .list();
    }

    public long countInvoices(Long customerId) {
        Session session = sessionFactory.getCurrentSession();
        return (Long) session.createQuery(
                "select count(i) from Invoice i where i.customerId = :id")
                .setParameter("id", customerId)
                .uniqueResult();
    }
}
