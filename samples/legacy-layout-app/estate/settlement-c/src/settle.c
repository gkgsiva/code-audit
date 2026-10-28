/* Result of proc iname=settle.pc: generated, do not edit. */
#include <stdio.h>
#include <sqlca.h>
static const char *sq0001 = "UPDATE payments SET status = 'SETTLED' WHERE status = 'DUE'";
int main(int argc, char **argv)
{
    sqlcxt((void **)0, &sqlctx, &sqlstm, &sqlfpn);
    return 0;
}
