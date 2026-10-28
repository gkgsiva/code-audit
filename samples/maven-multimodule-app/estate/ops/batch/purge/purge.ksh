#!/bin/ksh
# Purge: deletes audit rows older than 90 days.
. ../common/functions.ksh
log "purge start"
sqlplus -s "$DB_CONNECT" @purge.sql || exit 1
