package com.acme.orders.form;

import org.apache.struts.action.ActionForm;

public class OrderSearchForm extends ActionForm {

    private String customerName;
    private String status;

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
