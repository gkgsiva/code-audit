using System;
using System.Data.SqlClient;
using System.IO;

namespace NightlyExport
{
    /// <summary>
    /// Nightly invoice export: reads the day's invoices from BILLING and writes a CSV
    /// file to the finance share for the ledger team.
    /// </summary>
    class Program
    {
        static int Main(string[] args)
        {
            var cs = "Data Source=BILLSRV01;Initial Catalog=BILLING;Integrated Security=SSPI";
            using (var cn = new SqlConnection(cs))
            using (var cmd = new SqlCommand("SELECT INVOICE_ID, AMOUNT FROM INVOICE WHERE EXPORTED = 0", cn))
            using (var writer = new StreamWriter(@"\\fileserver\exports\invoices.csv"))
            {
                cn.Open();
                using (var rd = cmd.ExecuteReader())
                {
                    while (rd.Read())
                        writer.WriteLine(rd.GetInt32(0) + ";" + rd.GetDecimal(1));
                }
            }
            return 0;
        }
    }
}
