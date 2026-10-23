#!/bin/ksh
# Monthly billing: runs the billing-batch module.
java -cp /opt/acme/lib/billing-batch.jar com.acme.billing.BillingMain /opt/acme/config/app.properties || exit 1
