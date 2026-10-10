      *----------------------------------------------------------------*
      * CUSTDCL - DCLGEN for table CUSTOMER                            *
      *----------------------------------------------------------------*
           EXEC SQL DECLARE CUSTOMER TABLE
           ( CUST_ID                        INTEGER NOT NULL,
             CUST_NAME                      CHAR(40) NOT NULL,
             CUST_SEGMENT                   CHAR(1)
           ) END-EXEC.
       01  DCLCUSTOMER.
           10 CUST-ID              PIC S9(9) COMP.
           10 CUST-NAME            PIC X(40).
           10 CUST-SEGMENT         PIC X(1).
