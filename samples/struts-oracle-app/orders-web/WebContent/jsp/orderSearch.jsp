<%@ page contentType="text/html; charset=ISO-8859-1" import="java.util.*" %>
<%@ taglib uri="/WEB-INF/struts-html.tld" prefix="html" %>
<%@ taglib uri="/WEB-INF/struts-logic.tld" prefix="logic" %>
<%@ taglib uri="/WEB-INF/struts-bean.tld" prefix="bean" %>
<html>
<head><title><bean:message key="search.title"/></title></head>
<body>
<%@ include file="common/header.jspf" %>
<html:form action="/orderSearch">
  Customer: <html:text property="customerName"/>
  Status:
  <html:select property="status">
    <html:option value="">All</html:option>
    <html:option value="NEW">New</html:option>
    <html:option value="SHIPPED">Shipped</html:option>
  </html:select>
  <html:submit value="Search"/>
</html:form>
<table border="1">
  <tr><th>Order</th><th>Customer</th><th>Status</th></tr>
  <logic:iterate id="o" name="orders">
    <tr>
      <td><a href="orderDetail.do?id=<bean:write name="o" property="id"/>"><bean:write name="o" property="id"/></a></td>
      <td><bean:write name="o" property="customer"/></td>
      <td><bean:write name="o" property="status"/></td>
    </tr>
  </logic:iterate>
</table>
<%
  // scriptlet left over from the first version
  List orders = (List) request.getAttribute("orders");
  out.println("<p>" + (orders == null ? 0 : orders.size()) + " orders</p>");
%>
<%@ include file="common/footer.jspf" %>
</body>
</html>
