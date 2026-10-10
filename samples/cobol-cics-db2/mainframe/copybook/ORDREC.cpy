      *----------------------------------------------------------------*
      * ORDREC - order record layout                                   *
      *----------------------------------------------------------------*
       01  ORDER-REC.
           05  ORD-ID              PIC 9(8).
           05  ORD-CUST-ID         PIC 9(8).
           05  ORD-AMOUNT          PIC S9(9)V99 COMP-3.
           05  ORD-STATUS          PIC X(1).
               88  ORD-OPEN        VALUE 'O'.
               88  ORD-SHIPPED     VALUE 'S'.
           05  ORD-DATE            PIC X(10).
