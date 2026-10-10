<%@ page contentType="text/html;charset=UTF-8" %>
<%@ include file="../common/header.jspf" %>
<html>
<body>
  <h1>Invoice ${id}</h1>
  <form method="post" action="${id}/pay">
    <input type="text" name="reference"/>
    <input type="text" name="amount"/>
    <input type="submit" value="Mark as paid"/>
  </form>
</body>
</html>
