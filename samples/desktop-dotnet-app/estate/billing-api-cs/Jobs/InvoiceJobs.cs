using Dapper;
using Microsoft.Data.SqlClient;

namespace BillingApi.Jobs;

public class InvoiceJobs
{
    private readonly IConfiguration _config;

    public InvoiceJobs(IConfiguration config) => _config = config;

    public void CloseOverdue()
    {
        using var cn = new SqlConnection(_config.GetConnectionString("Billing"));
        cn.Execute("UPDATE INVOICE SET STATUS = 'O' WHERE DUE_DATE < GETDATE() AND STATUS = 'N'");
    }
}
