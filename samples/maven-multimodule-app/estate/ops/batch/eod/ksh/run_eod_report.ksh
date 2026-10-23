#!/bin/ksh
# End of day report: counts the day's orders.
sqlplus -s "$DB_CONNECT" @../sql/eod_report.sql || exit 1
