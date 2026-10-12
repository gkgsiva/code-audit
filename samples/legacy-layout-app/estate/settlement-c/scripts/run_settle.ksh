#!/bin/ksh
# Daily settlement run.
/opt/settlement/bin/settle $DB_CONNECT || exit 1
