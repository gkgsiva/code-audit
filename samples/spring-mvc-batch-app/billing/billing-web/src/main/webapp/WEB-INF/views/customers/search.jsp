<%@ page contentType="text/html;charset=UTF-8" %>
<%@ include file="../common/header.jspf" %>
<html>
<body>
  <h1>Customer search</h1>
  <form method="get" action="search">
    <select name="segment">
      <option value="RETAIL">Retail</option>
      <option value="CORPORATE">Corporate</option>
    </select>
    <input type="submit" value="Search"/>
  </form>
</body>
</html>
