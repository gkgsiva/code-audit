<%@ page contentType="text/html; charset=ISO-8859-1" %>
<%@ taglib uri="/WEB-INF/struts-html.tld" prefix="html" %>
<%@ taglib uri="/WEB-INF/struts-bean.tld" prefix="bean" %>
<%-- Login page: first screen of the application --%>
<html>
<head><title><bean:message key="login.title"/></title>
<link rel="stylesheet" href="../css/site.css"/></head>
<body>
<%@ include file="common/header.jspf" %>
<html:errors/>
<html:form action="/login">
  <table>
    <tr><td><bean:message key="login.username"/></td><td><html:text property="username"/></td></tr>
    <tr><td><bean:message key="login.password"/></td><td><html:password property="password"/></td></tr>
  </table>
  <html:submit value="Sign in"/>
</html:form>
<%@ include file="common/footer.jspf" %>
</body>
</html>
