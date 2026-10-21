VERSION 5.00
Object = "{86CF1D34-0C5F-11D2-A9FC-0000F8754DA1}#2.0#0"; "MSCOMCT2.OCX"
Begin VB.Form frmCustomer 
   BorderStyle     =   3  'Fixed Dialog
   Caption         =   "Customer search"
   ClientHeight    =   4500
   Begin VB.TextBox txtName 
      Height          =   315
   End
   Begin VB.TextBox txtCity 
      Height          =   315
   End
   Begin VB.ComboBox cboStatus 
      Height          =   315
      Style           =   2  'Dropdown List
   End
   Begin VB.CheckBox chkActive 
      Caption         =   "Active only"
   End
   Begin MSComCtl2.DTPicker dtpSince 
      Height          =   315
   End
   Begin VB.CommandButton cmdSearch 
      Caption         =   "&Search"
      Default         =   -1  'True
   End
   Begin VB.ListBox lstResults 
      Height          =   2000
   End
   Begin VB.Data datRegions 
      Caption         =   "Regions"
      Connect         =   "Access"
      DatabaseName    =   "C:\BillingDesk\regions.mdb"
      RecordSource    =   "SELECT REGION_CODE, REGION_NAME FROM REGION ORDER BY REGION_NAME"
   End
   Begin VB.Label lblName 
      Caption         =   "Name"
   End
End
Attribute VB_Name = "frmCustomer"
Attribute VB_GlobalNameSpace = False
Attribute VB_Creatable = False
Attribute VB_PredeclaredId = True
Attribute VB_Exposed = False
Option Explicit

Private Sub cmdSearch_Click()
    Dim sql As String
    Dim rs As ADODB.Recordset

    sql = "SELECT CUST_ID, CUST_NAME, CITY FROM CUSTOMER WHERE 1 = 1"
    If Trim$(txtName.Text) <> "" Then
        sql = sql & " AND CUST_NAME LIKE '" & Replace(txtName.Text, "'", "''") & "%'"
    End If
    If txtCity.Text <> "" Then sql = sql & " AND CITY = '" & txtCity.Text & "'"
    If chkActive.Value = vbChecked Then
        sql = sql & " AND STATUS = 'A'"
    End If
    sql = sql & " ORDER BY " & cboStatus.Text

    Set rs = New ADODB.Recordset
    rs.Open sql, modDb.Conn, adOpenStatic, adLockReadOnly
    lstResults.Clear
    Do While Not rs.EOF
        lstResults.AddItem rs!CUST_NAME
        rs.MoveNext
    Loop
    rs.Close
End Sub

Private Sub lstResults_DblClick()
    ' Show the selected customer's address
    Dim addr As String
    addr = modDb.LookupValue("SELECT ADDRESS FROM CUSTOMER WHERE CUST_NAME = '" & lstResults.Text & "'")
    MsgBox addr
End Sub
