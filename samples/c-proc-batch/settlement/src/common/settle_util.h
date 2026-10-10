#ifndef SETTLE_UTIL_H
#define SETTLE_UTIL_H

/* Shared helpers for the settlement programs. */
int settle_log(const char *msg);
int settle_archive(const char *file);
const char *settle_run_date(void);

#endif
