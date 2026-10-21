VERSION 1.0 CLASS
BEGIN
  MultiUse = -1  'True
  Persistable = 0  'NotPersistable
  DataBindingBehavior = 0  'vbNone
  DataSourceBehavior  = 0  'vbNone
  MTSTransactionMode  = 0  'NotAnMTSObject
END
Attribute VB_Name = "clsInvoice"
Attribute VB_GlobalNameSpace = False
Attribute VB_Creatable = True
Attribute VB_PredeclaredId = False
Attribute VB_Exposed = False
Option Explicit

Public Sub PrintOpen(ByVal asOf As Date)
    Dim cmd As ADODB.Command
    Dim rs As ADODB.Recordset
    Dim i As Integer

    Set cmd = New ADODB.Command
    Set cmd.ActiveConnection = modDb.Conn
    cmd.CommandType = adCmdStoredProc
    cmd.CommandText = "usp_OpenInvoices"
    Set rs = cmd.Execute

    For i = 1 To 3
        If i = 2 Then
            modDb.LogAction "reprint"
        ElseIf i = 3 And asOf > Date Then
            Exit For
        End If
    Next i
End Sub

Public Function PostToLedger(ByVal invoiceId As Long) As Boolean
    Dim http As Object
    On Error Resume Next
    Set http = CreateObject("MSXML2.ServerXMLHTTP")
    http.Open "POST", "http://ledger.acme.local/api/postings", False
    http.send "<posting invoice='" & invoiceId & "'/>"
    PostToLedger = (http.Status = 200)
End Function
