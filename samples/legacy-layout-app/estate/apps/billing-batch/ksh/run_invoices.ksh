#!/bin/ksh
# Monthly invoicing batch: set the environment, then run the Java invoicing program.
. /opt/billing/ksh/billing_env.ksh
java -cp $BILLING_HOME/lib/billing.jar com.acme.billing.InvoiceRun $BILLING_HOME/properties/billing.properties || exit 1
echo "invoices done"
