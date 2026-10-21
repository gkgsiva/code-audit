package com.acme.orders.client;

import com.acme.crm.CrmPort;
import com.acme.crm.CrmService;
import javax.xml.ws.BindingProvider;

/** Looks up the customer's account manager in the CRM. */
public class CrmClient {

    public String accountManager(String customerId) {
        CrmPort port = new CrmService().getCrmServicePort();
        return port.findAccountManager(customerId);
    }
}
