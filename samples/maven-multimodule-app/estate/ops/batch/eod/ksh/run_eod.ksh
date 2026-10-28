#!/bin/ksh
# End of day: closes the business day.
sqlplus -s "$DB_CONNECT" @../sql/eod_close.sql || exit 1
