VERSION 5.00
Begin VB.UserControl ctlAddress 
   ClientHeight    =   1500
   ClientWidth     =   4800
   Begin VB.TextBox txtStreet 
      Height          =   315
   End
   Begin VB.TextBox txtZip 
      Height          =   315
   End
End
Attribute VB_Name = "ctlAddress"
Attribute VB_GlobalNameSpace = False
Attribute VB_Creatable = True
Attribute VB_PredeclaredId = False
Attribute VB_Exposed = False
Option Explicit

Public Property Get Street() As String
    Street = txtStreet.Text
End Property

Public Property Let Street(ByVal value As String)
    txtStreet.Text = value
End Property
