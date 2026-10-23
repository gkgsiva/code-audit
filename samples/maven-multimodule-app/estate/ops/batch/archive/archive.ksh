#!/bin/ksh
# Archive: moves yesterday's reports to the archive folder.
. ../common/functions.ksh
log "archive start"
mv /data/acme/reports/*.csv /data/acme/archive/
