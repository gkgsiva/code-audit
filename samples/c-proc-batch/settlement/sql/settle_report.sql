-- Daily settlement report, called from run_settlement.ksh
SET LINESIZE 200
SPOOL /data/settlement/reports/settle_report.txt

SELECT m.merchant_name, l.amount, DECODE(l.status, 'P', 'Paid', 'Pending') AS status
  FROM settlement_lines l, merchants m
 WHERE l.merchant_id = m.merchant_id (+)
   AND l.run_date = TO_DATE('&1', 'YYYYMMDD');

SPOOL OFF
EXIT
