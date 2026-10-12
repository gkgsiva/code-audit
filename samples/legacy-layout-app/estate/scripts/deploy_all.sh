#!/bin/sh
# Copy the release to the servers.
for host in app01 app02; do
  scp -r release/ deploy@$host:/opt/acme/
done
