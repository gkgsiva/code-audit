<%@ page contentType="text/html; charset=ISO-8859-1" %>
<%@ taglib uri="/WEB-INF/struts-bean.tld" prefix="bean" %>
<html>
<head><title>Order detail</title></head>
<body>
<%@ include file="common/header.jspf" %>
<h2>Order <bean:write name="order" property="id"/></h2>
<p>Status: <bean:write name="order" property="status"/></p>
<p>Priority: <bean:write name="order" property="priority"/></p>
<p>Price: <%= request.getAttribute("price") %></p>
<!-- TODO: show order lines -->
<a href="orderSearch.do">Back</a>
<%@ include file="common/footer.jspf" %>
</body>
</html>
