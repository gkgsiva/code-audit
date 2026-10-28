#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include "settle_util.h"

static char run_date[9];

/* Append a line to the run log. */
int settle_log(const char *msg)
{
    FILE *log = fopen("/var/log/settlement/settle.log", "a");
    if (log == NULL) {
        return -1;
    }
    fprintf(log, "%s %s\n", settle_run_date(), msg);
    fclose(log);
    return 0;
}

/* Hand a processed file to the archive script. */
int settle_archive(const char *file)
{
    char cmd[512];
    snprintf(cmd, sizeof cmd, "/opt/settlement/scripts/archive.sh %s", file);
    return system(cmd);
}

const char *settle_run_date(void)
{
    time_t now = time(NULL);
    strftime(run_date, sizeof run_date, "%Y%m%d", localtime(&now));
    return run_date;
}
