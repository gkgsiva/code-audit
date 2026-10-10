package com.acme.billing.common.model;

import java.math.BigDecimal;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "INVOICES")
public class Invoice {

    @Id
    @Column(name = "INVOICE_ID")
    private Long id;

    @Column(name = "CUSTOMER_ID")
    private Long customerId;

    private BigDecimal amount;

    private String status;

    private Date dueDate;

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    public String getStatus() { return status; }
    public Date getDueDate() { return dueDate; }
}
