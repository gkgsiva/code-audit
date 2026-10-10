//ORDRPTP  PROC
//*----------------------------------------------------------------*
//* DB2 REPORT PROCEDURE                                           *
//*----------------------------------------------------------------*
//RPT      EXEC PGM=IKJEFT01
//STEPLIB  DD DSN=PROD.ORDERS.LOADLIB,DISP=SHR
//RPTOUT   DD SYSOUT=*
//SYSTSIN  DD *
  DSN SYSTEM(DB2P)
  RUN PROGRAM(ORDRPT) PLAN(ORDPLAN)
  END
/*
//         PEND
