/*
 * settle_send: reads the settlement file and publishes one MQ message per merchant
 * to the bank's settlement queue.
 */
#include <stdio.h>
#include <string.h>
#include <cmqc.h>
#include "settle_util.h"

int main(int argc, char **argv)
{
    MQHCONN hconn;
    MQHOBJ  hobj;
    MQOD    od = {MQOD_DEFAULT};
    MQMD    md = {MQMD_DEFAULT};
    MQPMO   pmo = {MQPMO_DEFAULT};
    MQLONG  cc, rc;
    char    line[256];
    FILE   *in;

    MQCONN("QMSETL", &hconn, &cc, &rc);
    strncpy(od.ObjectName, "SETTLE.TO.BANK", MQ_Q_NAME_LENGTH);
    MQOPEN(hconn, &od, MQOO_OUTPUT, &hobj, &cc, &rc);

    in = fopen("/data/settlement/out/settlement.dat", "r");
    while (in != NULL && fgets(line, sizeof line, in) != NULL) {
        MQPUT(hconn, hobj, &md, &pmo, (MQLONG) strlen(line), line, &cc, &rc);
    }
    if (in != NULL) {
        fclose(in);
    }
    MQCLOSE(hconn, &hobj, MQCO_NONE, &cc, &rc);
    MQDISC(&hconn, &cc, &rc);
    settle_log("settle_send done");
    settle_archive("/data/settlement/out/settlement.dat");
    return 0;
}
