<Global.Microsoft.VisualBasic.CompilerServices.DesignerGenerated()> _
Partial Class MainForm
    Inherits System.Windows.Forms.Form

    'Required by the Windows Form Designer
    Private components As System.ComponentModel.IContainer

    <System.Diagnostics.DebuggerStepThrough()> _
    Private Sub InitializeComponent()
        Me.txtCustomer = New System.Windows.Forms.TextBox()
        Me.dtpFrom = New System.Windows.Forms.DateTimePicker()
        Me.cboStatus = New System.Windows.Forms.ComboBox()
        Me.btnSearch = New System.Windows.Forms.Button()
        Me.gridOrders = New System.Windows.Forms.DataGridView()
        Me.SuspendLayout()
        Me.btnSearch.Text = "Search"
        Me.Text = "Orders"
        Me.ResumeLayout(False)
    End Sub

    Friend WithEvents txtCustomer As System.Windows.Forms.TextBox
    Friend WithEvents dtpFrom As System.Windows.Forms.DateTimePicker
    Friend WithEvents cboStatus As System.Windows.Forms.ComboBox
    Friend WithEvents btnSearch As System.Windows.Forms.Button
    Friend WithEvents gridOrders As System.Windows.Forms.DataGridView
End Class
