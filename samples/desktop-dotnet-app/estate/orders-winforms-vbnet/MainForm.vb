Imports System.Windows.Forms

Public Class MainForm

    Private ReadOnly repo As New OrderRepository()

    Private Sub btnSearch_Click(sender As Object, e As EventArgs) Handles btnSearch.Click
        Try
            gridOrders.DataSource = repo.Search(txtCustomer.Text, cboStatus.Text, dtpFrom.Value)
        Catch ex As Exception
            MessageBox.Show(ex.Message)
        End Try
    End Sub

    Private Sub gridOrders_CellDoubleClick(sender As Object, e As DataGridViewCellEventArgs) Handles gridOrders.CellDoubleClick
        Dim dlg As New OrderDetailForm()
        dlg.ShowDialog(Me)
    End Sub
End Class

Public Class OrderDetailForm
    Inherits Form

    Private txtNotes As New TextBox()

    Public Sub New()
        Me.Controls.Add(txtNotes)
    End Sub
End Class
