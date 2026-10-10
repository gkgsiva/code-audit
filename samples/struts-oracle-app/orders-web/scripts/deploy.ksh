#!/bin/ksh
# Deploy orders.war to the Tomcat servers and recalculate prices.

WAR=${1:-dist/orders.war}
TOMCAT_HOSTS="appsrv01 appsrv02"

for host in $TOMCAT_HOSTS; do
    echo "Deploying to $host"
    scp "$WAR" tomcat@$host:/opt/tomcat/webapps/orders.war
done

# nightly recalculation
sqlplus -s orders_app/"$ORDERS_DB_PASSWORD"@ORDP <<SQL
EXEC PKG_ORDER_PRICING.RECALC_ALL;
EXIT;
SQL
