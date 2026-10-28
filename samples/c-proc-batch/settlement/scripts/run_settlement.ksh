#!/bin/ksh
# Nightly settlement: load, extract, send to the bank (CFT), publish on MQ.
. /opt/settlement/etc/settle.env
BIN=/opt/settlement/bin
RUN_DATE=${1:-$(date +%Y%m%d)}
OUT=/data/settlement/out

$BIN/settle_load $RUN_DATE || exit 1

sqlplus -s $DB_USER/$DB_PASS@SETLP <<EOSQL
SET PAGESIZE 0 FEEDBACK OFF
SPOOL $OUT/settlement.dat
SELECT merchant_id || ';' || TO_CHAR(amount, 'FM999999990.00')
  FROM settlement_lines
 WHERE run_date = TO_DATE('$RUN_DATE', 'YYYYMMDD');
SPOOL OFF
EXIT
EOSQL

CFTUTIL SEND PART=BANKFR, IDF=SETTLE, FNAME=$OUT/settlement.dat
$BIN/settle_send $RUN_DATE
sqlplus -s $DB_USER/$DB_PASS@SETLP @/opt/settlement/sql/settle_report.sql $RUN_DATE
