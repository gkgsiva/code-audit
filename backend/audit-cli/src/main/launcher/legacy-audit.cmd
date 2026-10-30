@echo off
rem Runs legacy-audit.jar from the folder this script is in. Put the folder on PATH so that
rem `legacy-audit scan`, Copilot's commands and .vscode\mcp.json find the tool.
java %LEGACY_AUDIT_JAVA_OPTS% -jar "%~dp0legacy-audit.jar" %*
