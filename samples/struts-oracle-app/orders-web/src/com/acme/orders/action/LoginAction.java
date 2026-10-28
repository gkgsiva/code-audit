package com.acme.orders.action;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.log4j.Logger;
import org.apache.struts.action.Action;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;

import com.acme.orders.form.LoginForm;
import com.acme.orders.service.OrderService;

/**
 * Authenticates a user against the USERS table.
 */
public class LoginAction extends Action {

    private static final Logger LOG = Logger.getLogger(LoginAction.class);

    public ActionForward execute(ActionMapping mapping, ActionForm form,
            HttpServletRequest request, HttpServletResponse response) throws Exception {
        LoginForm loginForm = (LoginForm) form;
        OrderService service = new OrderService();

        // Plain-text password check, kept from the original system
        if (service.authenticate(loginForm.getUsername(), loginForm.getPassword())) {
            HttpSession session = request.getSession(true);
            session.setAttribute("user", loginForm.getUsername());
            LOG.info("Login ok for " + loginForm.getUsername());
            return mapping.findForward("success");
        }
        LOG.warn("Login failed for " + loginForm.getUsername());
        return mapping.findForward("failure");
    }
}
