@echo off
rem Runs legacy-audit.jar from the folder this script is in. Put the folder on PATH so that
rem `legacy-audit scan`, Copilot's commands and .vscode\mcp.json find the tool.
rem Up to half the memory for the heap and shared copies of repeated text: a 2-million-line
rem estate then scans on an 8 GB laptop. LEGACY_AUDIT_JAVA_OPTS comes last and wins (e.g. -Xmx6g).
java -XX:MaxRAMPercentage=50 -XX:+UseStringDeduplication %LEGACY_AUDIT_JAVA_OPTS% -jar "%~dp0legacy-audit.jar" %*
