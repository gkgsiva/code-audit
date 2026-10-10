#!/bin/sh
# Copy a processed settlement file to the archive server and remove it locally.
FILE=$1
ARCHIVE_HOST=archive01.acme.local
scp "$FILE" settle@$ARCHIVE_HOST:/archive/settlement/
rm -f "$FILE"
