using Dapper;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Data.SqlClient;

namespace BillingApi.Controllers;

[ApiController]
[Route("api/[controller]")]
public class InvoicesController : ControllerBase
{
    private readonly string _cs;

    public InvoicesController(IConfiguration config) => _cs = config.GetConnectionString("Billing")!;

    [HttpGet("{id}")]
    public IActionResult Get(int id)
    {
        using var cn = new SqlConnection(_cs);
        var invoice = cn.QueryFirstOrDefault("SELECT INVOICE_ID, AMOUNT, STATUS FROM INVOICE WHERE INVOICE_ID = @id", new { id });
        return invoice == null ? NotFound() : Ok(invoice);
    }

    [HttpPost]
    public IActionResult Create(InvoiceDto dto)
    {
        using var cn = new SqlConnection(_cs);
        cn.Execute("INSERT INTO INVOICE (CUST_ID, AMOUNT, STATUS) VALUES (@CustId, @Amount, 'N')", dto);
        return Ok();
    }
}

public record InvoiceDto(int CustId, decimal Amount);
