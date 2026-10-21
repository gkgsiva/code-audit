Imports System.Configuration
Imports System.Data.SqlClient
Imports System.Text
Imports Dapper

Public Class OrderRepository

    Private Const BaseSelect As String = "SELECT o.ORDER_ID, o.ORDER_DATE, c.NAME FROM ORDERS o " &
        "JOIN CUSTOMER c ON c.CUST_ID = o.CUST_ID"

    Private Function Connect() As SqlConnection
        Return New SqlConnection(ConfigurationManager.ConnectionStrings("Orders").ConnectionString)
    End Function

    Public Function Search(customer As String, status As String, since As Date) As DataTable
        Dim sb As New StringBuilder(BaseSelect)
        sb.Append(" WHERE o.ORDER_DATE >= @since")
        If customer <> "" Then
            sb.Append(" AND c.NAME LIKE '" & customer & "%'")
        End If
        Select Case status
            Case "Open"
                sb.Append(" AND o.STATUS = 'O'")
            Case "Closed"
                sb.Append(" AND o.STATUS = 'C'")
        End Select
        sb.AppendFormat(" ORDER BY {0}", If(status = "", "o.ORDER_DATE", "c.NAME"))

        Using cn = Connect()
            Using cmd As New SqlCommand(sb.ToString(), cn)
                cmd.Parameters.AddWithValue("@since", since)
                Dim table As New DataTable()
                Dim da As New SqlDataAdapter(cmd)
                da.Fill(table)
                Return table
            End Using
        End Using
    End Function

    Public Function CountLines(orderId As Integer) As Integer
        Using cn = Connect()
            Return cn.ExecuteScalar(Of Integer)("SELECT COUNT(*) FROM ORDER_LINE WHERE ORDER_ID = @id", New With {.id = orderId})
        End Using
    End Function

    Public Sub Archive(cutoff As Date)
        Dim sql As String = "DELETE FROM ORDERS WHERE STATUS = 'C'"
        sql &= " AND ORDER_DATE < '" & cutoff.ToString("yyyy-MM-dd") & "'"
        Using cn = Connect()
            cn.Open()
            Dim cmd = cn.CreateCommand()
            cmd.CommandText = sql
            cmd.ExecuteNonQuery()
        End Using
        IO.File.AppendAllText("\\fileserver\orders\archive.log", "archived " & cutoff.ToString())
    End Sub
End Class
