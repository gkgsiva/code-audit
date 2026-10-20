-- SQL*Loader control file: load the monthly usage feed into INVOICE_LINE
LOAD DATA
INFILE '/data/billing/in/usage.dat'
APPEND INTO TABLE INVOICE_LINE
FIELDS TERMINATED BY ';'
(ACCOUNT_ID, AMOUNT, CURRENCY)
