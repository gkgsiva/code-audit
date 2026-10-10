<%@ page isErrorPage="true" contentType="text/html; charset=ISO-8859-1" %>
<html>
<head><title>Error</title></head>
<body>
<h2>Something went wrong</h2>
<p><%= exception == null ? "Unknown error" : exception.getMessage() %></p>
<a href="login.jsp">Start again</a>
</body>
</html>
