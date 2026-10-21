Attribute VB_Name = "modDb"
Option Explicit

Public Conn As ADODB.Connection
Private Const CONN_STR = "Provider=SQLOLEDB.1;Data Source=BILLSRV01;Initial Catalog=BILLING;User ID=billing;Password=Winter2003"
Private Const SQL_AUDIT = "INSERT INTO AUDIT_LOG (EVENT_TS, USER_NAME, ACTION) VALUES (GETDATE(), ?, ?)"

Private Declare Function GetUserName Lib "advapi32.dll" Alias "GetUserNameA" (ByVal lpBuffer As String, nSize As Long) As Long

Public Sub OpenDb()
    On Error GoTo Failed
    Set Conn = New ADODB.Connection
    Conn.Open CONN_STR
    Exit Sub
Failed:
    MsgBox "Cannot connect: " & Err.Description
End Sub

Public Function LookupValue(ByVal sql As String) As String
    Dim rs As ADODB.Recordset
    Set rs = Conn.Execute(sql)
    If Not rs.EOF Then LookupValue = rs.Fields(0).Value
End Function

Public Sub LogAction(ByVal action As String)
    Dim cmd As New ADODB.Command
    Set cmd.ActiveConnection = Conn
    cmd.CommandText = SQL_AUDIT
    cmd.Execute , Array(CurrentUser(), action)
End Sub

Public Sub ExportCustomers(ByVal path As String)
    Dim f As Integer
    f = FreeFile
    Open "C:\BillingDesk\export\customers.csv" For Output As #f
    Print #f, "id;name"
    Close #f
End Sub

Private Function CurrentUser() As String
    Dim s As String * 255
    Dim n As Long
    n = 255
    GetUserName s, n
    CurrentUser = Left$(s, n - 1)
End Function
