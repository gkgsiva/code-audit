VERSION 5.00
Object = "{831FDD16-0C5C-11D2-A9FC-0000F8754DA1}#2.0#0"; "MSCOMCTL.OCX"
Begin VB.Form frmMain 
   Caption         =   "Billing Desk"
   ClientHeight    =   6000
   ClientWidth     =   9000
   StartUpPosition =   2  'CenterScreen
   Begin MSComctlLib.StatusBar sbMain 
      Align           =   2  'Align Bottom
      Height          =   300
   End
   Begin VB.CommandButton cmdCustomers 
      Caption         =   "&Customers"
      Height          =   495
   End
   Begin VB.CommandButton cmdInvoices 
      Caption         =   "&Print invoices"
      Height          =   495
   End
   Begin VB.Menu mnuFile 
      Caption         =   "&File"
      Begin VB.Menu mnuExit 
         Caption         =   "E&xit"
      End
   End
End
Attribute VB_Name = "frmMain"
Attribute VB_GlobalNameSpace = False
Attribute VB_Creatable = False
Attribute VB_PredeclaredId = True
Attribute VB_Exposed = False
Option Explicit

Private Sub Form_Load()
    ' Open the shared connection once
    modDb.OpenDb
    sbMain.SimpleText = "Connected"
End Sub

Private Sub cmdCustomers_Click()
    frmCustomer.Show vbModal
End Sub

Private Sub cmdInvoices_Click()
    Dim inv As clsInvoice
    Set inv = New clsInvoice
    inv.PrintOpen Date
End Sub

Private Sub mnuExit_Click()
    Unload Me
End Sub
