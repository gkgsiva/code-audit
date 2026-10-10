# Settlement batch build (Oracle Pro*C + IBM MQ)
CC = cc
PROC = proc
CFLAGS = -O2 -I$(ORACLE_HOME)/precomp/public -I/opt/mqm/inc -Isrc/common
LIBS = -L$(ORACLE_HOME)/lib -lclntsh -L/opt/mqm/lib64 -lmqm

all: settle_load settle_send

src/settle_load.c: src/settle_load.pc
	$(PROC) iname=src/settle_load.pc oname=src/settle_load.c sqlcheck=semantics

settle_load: src/settle_load.c src/common/settle_util.c
	$(CC) $(CFLAGS) -o settle_load src/settle_load.c src/common/settle_util.c $(LIBS)

settle_send: src/settle_send.c src/common/settle_util.c
	$(CC) $(CFLAGS) -o settle_send src/settle_send.c src/common/settle_util.c $(LIBS)

clean:
	rm -f settle_load settle_send src/settle_load.c *.o
