#!/bin/sh
# Launch the Spring Batch invoice job (called by Autosys job BIL_INVOICE).
APP_HOME=/opt/billing
CP=$APP_HOME/lib/billing-batch.jar:$APP_HOME/lib/*

java -cp "$CP" org.springframework.batch.core.launch.support.CommandLineJobRunner \
    jobs/invoice-job.xml invoiceJob run.date=$(date +%Y%m%d)
rc=$?
if [ $rc -ne 0 ]; then
    echo "invoice job failed" | mailx -s "BILLING invoice job failed rc=$rc" billing-ops@acme.local
fi
exit $rc
