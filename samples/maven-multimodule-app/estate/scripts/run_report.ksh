#!/bin/ksh
# Daily report: runs the report-batch module.
java -cp /opt/acme/lib/report-batch.jar com.acme.report.ReportMain /opt/acme/config/app.properties || exit 1
