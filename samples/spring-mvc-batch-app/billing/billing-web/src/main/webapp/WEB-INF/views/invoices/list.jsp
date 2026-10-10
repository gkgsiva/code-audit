<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ include file="../common/header.jspf" %>
<html>
<body>
  <h1>Open invoices</h1>
  <table>
    <c:forEach var="inv" items="${invoices}">
      <tr>
        <td>${inv.INVOICE_ID}</td>
        <td>${inv.AMOUNT}</td>
        <td><a href="invoices/${inv.INVOICE_ID}">Details</a></td>
      </tr>
    </c:forEach>
  </table>
</body>
</html>
