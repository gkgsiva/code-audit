package com.acme.orders.action;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.struts.action.Action;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;

import com.acme.orders.service.OrderService;

public class OrderDetailAction extends Action {

    public ActionForward execute(ActionMapping mapping, ActionForm form,
            HttpServletRequest request, HttpServletResponse response) throws Exception {
        long orderId = Long.parseLong(request.getParameter("id"));
        OrderService service = new OrderService();
        Map order = service.getOrder(orderId);
        if (order == null) {
            return mapping.findForward("notFound");
        }
        request.setAttribute("order", order);
        request.setAttribute("price", service.priceOrder(orderId)); // SOAP call
        return mapping.findForward("success");
    }
}
