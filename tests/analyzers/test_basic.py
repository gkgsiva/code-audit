# ruff: noqa: E501  (fixtures keep the long lines of real project files)
"""basic analyzer: VB6 projects, forms and modules, and VB.NET classes (Windows Forms, services,
ASMX), with SQL rebuilt from string building."""

from __future__ import annotations

from legacy_audit.analyzers.basic import BasicAnalyzer, logical_lines, strip_comment
from tests.analyzers.helpers import entities, run

VBP = """Type=Exe
Reference=*\\G{2A75196C-D9EB-4129-B803-931327F72D5C}#2.8#0#C:\\Program Files\\Common Files\\System\\ado\\msado15.dll#Microsoft ActiveX Data Objects 2.8 Library
Object={831FDD16-0C5C-11D2-A9FC-0000F8754DA1}#2.0#0; MSCOMCTL.OCX
Form=frmMain.frm
Module=modDb; modDb.bas
Startup="frmMain"
Name="BillingDesk"
"""

FRM = """VERSION 5.00
Begin VB.Form frmCustomer
   Caption         =   "Customer"
   Begin VB.TextBox txtName
      Height          =   285
   End
   Begin VB.ComboBox cboCity
   End
   Begin VB.CommandButton cmdFind
      Caption         =   "Find"
   End
   Begin VB.Data datRegion
      DatabaseName    =   "C:\\Billing\\regions.mdb"
      RecordSource    =   "SELECT REGION_CODE FROM REGION"
   End
End
Attribute VB_Name = "frmCustomer"
Option Explicit

Private Sub cmdFind_Click()
    Dim sql As String
    Dim rs As ADODB.Recordset
    sql = "SELECT CUST_ID, CUST_NAME FROM CUSTOMER WHERE 1 = 1"
    If txtName.Text <> "" Then
        sql = sql & " AND CUST_NAME LIKE '" & txtName.Text & "%'"
    End If
    sql = sql & _
          " ORDER BY CUST_NAME"
    Set rs = gConn.Execute(sql)
    frmMain.Show
End Sub

Private Sub Export()
    Open "C:\\Billing\\export\\customers.csv" For Output As #1
    Close #1
End Sub
"""

BAS = """Attribute VB_Name = "modDb"
Public gConn As ADODB.Connection
Private Const CONN_STR As String = "Provider=SQLOLEDB;Data Source=BILLSRV01;Initial Catalog=BILLING;User Id=sa;Password=billing1"

Public Sub OpenDb()
    Set gConn = New ADODB.Connection
    gConn.Open CONN_STR
End Sub

Public Function OpenInvoices() As ADODB.Recordset
    Dim cmd As New ADODB.Command
    cmd.CommandType = adCmdStoredProc
    cmd.CommandText = "usp_OpenInvoices"
    Set OpenInvoices = cmd.Execute
End Function
"""

VBNET = """Imports System.Data.SqlClient
Imports System.Text
Imports Dapper

Public Class MainForm
    Inherits System.Windows.Forms.Form

    Private Sub btnSearch_Click(sender As Object, e As EventArgs) Handles btnSearch.Click
        Dim sb As New StringBuilder()
        sb.Append("SELECT ORDER_ID FROM ORDERS WHERE 1 = 1")
        If chkOpen.Checked Then
            sb.AppendFormat(" AND STATUS = '{0}'", cboStatus.Text)
        End If
        Using cn As New SqlConnection(My.Settings.Orders)
            Dim cmd As New SqlCommand(sb.ToString(), cn)
            cmd.ExecuteReader()
            Dim n = cn.ExecuteScalar(Of Integer)("SELECT COUNT(*) FROM ORDER_LINE WHERE ORDER_ID = @id", New With {.id = 1})
        End Using
        Dim f As New OrderDetailForm()
        f.ShowDialog()
    End Sub
End Class
"""

DESIGNER = """Partial Class MainForm
    Inherits System.Windows.Forms.Form
    Private Sub InitializeComponent()
        Me.txtCustomer = New System.Windows.Forms.TextBox()
        Me.cboStatus = New System.Windows.Forms.ComboBox()
        Me.chkOpen = New System.Windows.Forms.CheckBox()
        Me.btnSearch = New System.Windows.Forms.Button()
    End Sub
    Friend WithEvents txtCustomer As System.Windows.Forms.TextBox
End Class
"""

SVC = """Imports System.ServiceProcess
Public Class MailerService
    Inherits ServiceBase
    Protected Overrides Sub OnStart(ByVal args() As String)
    End Sub
End Class
"""

ASMX = """Imports System.Web.Services
<WebService(Namespace:="http://tempuri.org/")>
Public Class ContactService
    Inherits WebService
    <WebMethod()>
    Public Function GetContactName(ByVal id As Integer) As String
        Return ""
    End Function
End Class
"""


def _run(path: str, text: str):  # type: ignore[no-untyped-def]
    return run(BasicAnalyzer(), path, text)


def test_comment_and_continuation_lines() -> None:
    assert strip_comment("x = \"it's\" ' note") == 'x = "it\'s" '
    assert strip_comment("Rem old code") == ""
    assert logical_lines("a = 1 & _\n    2\nb = 3") == [(1, "a = 1 & 2"), (3, "b = 3")]


def test_vb6_project_com_references_and_startup() -> None:
    facts = _run("vb6/BillingDesk.vbp", VBP)
    libs = {(lb.artifact, lb.source, lb.scope) for lb in entities(facts, "libraries")}
    assert libs == {
        ("Microsoft ActiveX Data Objects 2.8 Library", "com", "com-reference"),
        ("MSCOMCTL.OCX", "com", "activex-control"),
    }
    assert [f.rule_id for f in entities(facts, "findings")] == ["vb6-activex-control"]
    assert [(e.name, e.kind) for e in entities(facts, "entry_points")] == [
        ("BillingDesk", "desktop-app")
    ]
    assert [(r.kind, r.target) for r in facts.refs] == [("forwards", "frmMain")]


def test_vb6_form_controls_data_control_and_rebuilt_sql() -> None:
    facts = _run("vb6/frmCustomer.frm", FRM)
    [screen] = entities(facts, "screens")
    assert (screen.name, screen.technology, screen.fields_count) == ("frmCustomer", "vb6", 2)
    sql = {s.source: s for s in entities(facts, "sql_statements")}
    assert sql["dao"].text == "SELECT REGION_CODE FROM REGION"
    built = sql["ado"]
    assert built.text == (
        "SELECT CUST_ID, CUST_NAME FROM CUSTOMER WHERE 1 = 1 AND CUST_NAME LIKE '?%' "
        "ORDER BY CUST_NAME"
    )
    assert built.dynamic and "concatenation" in built.built_with
    found = {(i.type, i.target) for i in entities(facts, "integrations")}
    assert found == {
        ("DB", "access://regions.mdb"),
        ("FILE", "C:\\Billing\\export\\customers.csv"),
    }
    assert [(r.kind, r.target) for r in facts.refs] == [("forwards", "frmMain")]


def test_vb6_module_connection_and_stored_procedure() -> None:
    facts = _run("vb6/modDb.bas", BAS)
    [db] = entities(facts, "integrations")
    assert (db.target, db.protocol) == ("sqlserver://BILLSRV01/BILLING", "ado")
    [finding] = entities(facts, "findings")
    assert finding.rule_id == "connection-string-password" and finding.line == 3
    [call] = entities(facts, "sql_statements")
    assert (call.type, call.procedures) == ("CALL", ["USP_OPENINVOICES"])


def test_vbnet_form_string_builder_dapper_and_navigation() -> None:
    facts = _run("orders/MainForm.vb", VBNET)
    [screen] = entities(facts, "screens")
    assert (screen.name, screen.technology) == ("MainForm", "winforms")
    sql = {s.source: s.text for s in entities(facts, "sql_statements")}
    assert sql == {
        "ado.net": "SELECT ORDER_ID FROM ORDERS WHERE 1 = 1 AND STATUS = '?'",
        "dapper": "SELECT COUNT(*) FROM ORDER_LINE WHERE ORDER_ID = @id",
    }
    assert ("forwards", "OrderDetailForm") in {(r.kind, r.target) for r in facts.refs}
    # the designer file names the same screen (one id) and counts its input controls
    [designer] = entities(_run("orders/MainForm.Designer.vb", DESIGNER), "screens")
    assert designer.id == screen.id and designer.fields_count == 3


def test_vbnet_windows_service_and_asmx_operation() -> None:
    facts = _run("svc/MailerService.vb", SVC)
    assert [e.kind for e in entities(facts, "entry_points")] == ["windows-service"]
    [svc] = entities(_run("web/ContactService.asmx.vb", ASMX), "services")
    assert (svc.kind, svc.handler) == ("soap-operation", "ContactService.GetContactName")
