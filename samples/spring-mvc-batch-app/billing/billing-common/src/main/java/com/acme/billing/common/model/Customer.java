package com.acme.billing.common.model;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "CUSTOMERS")
public class Customer {

    @Id
    private Long id;

    private String name;

    private String segment;

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getSegment() { return segment; }
}
