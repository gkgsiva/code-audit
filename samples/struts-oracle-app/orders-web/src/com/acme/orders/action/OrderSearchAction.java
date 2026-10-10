package com.acme.orders.action;

import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.struts.action.Action;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;

import com.acme.orders.form.OrderSearchForm;
import com.acme.orders.service.OrderService;

public class OrderSearchAction extends Action {

    public ActionForward execute(ActionMapping mapping, ActionForm form,
            HttpServletRequest request, HttpServletResponse response) throws Exception {
        OrderSearchForm search = (OrderSearchForm) form;
        OrderService service = new OrderService();
        /* page size is fixed at 50 rows (ROWNUM in the DAO) */
        List orders = service.searchOrders(search.getCustomerName(), search.getStatus());
        request.setAttribute("orders", orders);
        return mapping.findForward("success");
    }
}
