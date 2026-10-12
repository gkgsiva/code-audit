<%@ taglib uri="/WEB-INF/struts-bean.tld" prefix="bean" %>
<html>
<body>
<h1><bean:message key="orders.title"/></h1>
<form action="orderList.do" method="get">
  <input type="text" name="customer"/>
  <input type="submit" value="Search"/>
</form>
</body>
</html>
