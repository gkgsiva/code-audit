# ruff: noqa: E501  (fixtures keep the long lines of real project files)
"""csharp analyzer: Windows Forms, Entity Framework, Dapper and ADO.NET SQL built in code,
ASP.NET controllers, Hangfire jobs, Windows services and integrations."""

from __future__ import annotations

from legacy_audit.analyzers.csharp import CSharpAnalyzer
from tests.analyzers.helpers import entities, run

REPO = """using System.Data;
using System.Data.SqlClient;
using System.Text;
using Dapper;

public class StockRepository
{
    private const string Cs = "Server=INVSRV01;Database=INVENTORY;Integrated Security=true";

    public IEnumerable<StockItem> Find(string text, string warehouse)
    {
        var sql = new StringBuilder("SELECT s.ID FROM STOCK_ITEM s");
        if (!string.IsNullOrEmpty(text))
            sql.Append($" WHERE s.SKU LIKE '{text}%'");
        using var cn = new SqlConnection(Cs);
        return cn.Query<StockItem>(sql.ToString());
    }

    public int Restock(int threshold)
    {
        string query = "UPDATE STOCK_ITEM SET REORDER = 1 WHERE QUANTITY < " + threshold;
        using var cmd = new SqlCommand(query);
        return cmd.ExecuteNonQuery();
    }

    public void Report(string sortColumn)
    {
        var cmd = new SqlCommand();
        cmd.CommandType = CommandType.StoredProcedure;
        cmd.CommandText = "dbo.usp_StockReport";
        var adapter = new SqlDataAdapter(string.Format("SELECT * FROM STOCK_ITEM ORDER BY {0}", sortColumn), Cs);
    }
}
"""
CONTEXT = """using Microsoft.EntityFrameworkCore;
using System.ComponentModel.DataAnnotations.Schema;

[Table("STOCK_ITEM")]
public class StockItem { public int Id { get; set; } }

public class InventoryContext : DbContext
{
    public DbSet<StockItem> StockItems { get; set; }
}

public class StockDialog
{
    private readonly InventoryContext db = new InventoryContext();
    public void Save(StockItem item)
    {
        var existing = db.StockItems.FirstOrDefault(s => s.Id == item.Id);
        db.StockItems.Add(item);
        db.SaveChanges();
    }
}
"""
API = """using Microsoft.AspNetCore.Mvc;
using Hangfire;

[ApiController]
[Route("api/[controller]")]
public class InvoicesController : ControllerBase
{
    [HttpGet("{id}")]
    public IActionResult Get(int id) => Ok();

    [HttpPost]
    public IActionResult Create(Invoice inv) => Ok();
}

public static class Jobs
{
    public static void Register()
    {
        RecurringJob.AddOrUpdate("close-invoices", () => Close(), Cron.Daily(2));
    }
    public static void Close() { }
}
"""
FORM = """using System.Windows.Forms;
using System.Net.Mail;
using System.Messaging;
using System.IO;

public partial class OrderForm : Form
{
    private void btnSend_Click(object sender, EventArgs e)
    {
        new SmtpClient("smtp.acme.local").Send("a@b", "c@d", "s", "b");
        var q = new MessageQueue(@".\\private$\\orders");
        File.WriteAllText(@"\\\\fileserver\\orders\\last.txt", "x");
        new DetailForm().ShowDialog();
    }
}
"""
DESIGNER = """partial class OrderForm
{
    private void InitializeComponent()
    {
        this.txtName = new System.Windows.Forms.TextBox();
        this.cboStatus = new System.Windows.Forms.ComboBox();
        this.btnSend = new System.Windows.Forms.Button();
    }
}
"""
MAIN = """using System.Windows.Forms;
static class Program
{
    [STAThread]
    static void Main()
    {
        Application.Run(new OrderForm());
    }
}
"""
SVC = """using System.ServiceProcess;
public class MailerService : ServiceBase
{
    protected override void OnStart(string[] args) { }
}
"""


def _run(path: str, text: str):  # type: ignore[no-untyped-def]
    return run(CSharpAnalyzer(), path, text)


def test_dapper_and_ado_net_sql_rebuilt_from_code() -> None:
    facts = _run("inv/Data/StockRepository.cs", REPO)
    sql = [(s.source, s.type, s.text) for s in entities(facts, "sql_statements")]
    assert sql == [
        ("dapper", "SELECT", "SELECT s.ID FROM STOCK_ITEM s WHERE s.SKU LIKE '?%'"),
        ("ado.net", "UPDATE", "UPDATE STOCK_ITEM SET REORDER = 1 WHERE QUANTITY < ?"),
        ("ado.net", "CALL", "CALL dbo.usp_StockReport"),
        ("ado.net", "SELECT", "SELECT * FROM STOCK_ITEM ORDER BY ?"),
    ]
    first = entities(facts, "sql_statements")[0]
    assert {"interpolation", "string-builder", "conditional"} <= set(first.built_with)
    [db] = entities(facts, "integrations")
    assert db.target == "sqlserver://INVSRV01/INVENTORY"


def test_entity_framework_entities_and_orm_calls() -> None:
    facts = _run("inv/Data/InventoryContext.cs", CONTEXT)
    entity = {s.name: s.data for s in facts.symbols if s.kind == "jpa-entity"}
    assert entity["StockItem"]["table"] == "STOCK_ITEM"
    assert entity["StockItems"]["entity"] == "StockItem"
    orm = [(s.type, s.source, s.parsed) for s in entities(facts, "sql_statements")]
    assert orm == [("SELECT", "entity-framework", False), ("INSERT", "entity-framework", False)]


def test_api_controller_routes_and_hangfire_job() -> None:
    facts = _run("api/Controllers/InvoicesController.cs", API)
    routes = sorted((s.kind, s.name) for s in entities(facts, "services"))
    assert routes == [
        ("rest-operation", "GET /api/Invoices/{id}"),
        ("rest-operation", "POST /api/Invoices"),
    ]
    [job] = entities(facts, "batch_jobs")
    assert (job.name, job.scheduler, job.schedule) == (
        "close-invoices",
        "hangfire",
        "daily at 02:00",
    )


def test_windows_form_designer_fields_and_integrations() -> None:
    facts = _run("orders/OrderForm.cs", FORM)
    [screen] = entities(facts, "screens")
    assert (screen.name, screen.technology) == ("OrderForm", "winforms")
    found = {(i.type, i.target) for i in entities(facts, "integrations")}
    assert found == {
        ("SMTP", "smtp.acme.local"),
        ("MQ", ".\\private$\\orders"),
        ("FILE", "\\\\fileserver\\orders\\last.txt"),
    }
    assert ("forwards", "DetailForm") in {(r.kind, r.target) for r in facts.refs}
    [designer] = entities(_run("orders/OrderForm.Designer.cs", DESIGNER), "screens")
    assert designer.id == screen.id and designer.fields_count == 2


def test_desktop_main_and_windows_service_entries() -> None:
    [main] = entities(_run("orders/Program.cs", MAIN), "entry_points")
    assert (main.name, main.kind) == ("Program.Main", "desktop-app")
    [svc] = entities(_run("svc/MailerService.cs", SVC), "entry_points")
    assert (svc.name, svc.kind) == ("MailerService", "windows-service")
