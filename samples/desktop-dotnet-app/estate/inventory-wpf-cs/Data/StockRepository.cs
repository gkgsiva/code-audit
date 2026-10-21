using System.Collections.Generic;
using System.Data;
using System.Data.SqlClient;
using System.Text;
using Dapper;

namespace InventoryApp.Data
{
    public class StockRepository
    {
        private const string ConnectionString = "Server=INVSRV01;Database=INVENTORY;Integrated Security=true";

        public IEnumerable<StockItem> Find(string text, string warehouse)
        {
            var sql = new StringBuilder("SELECT s.ID, s.SKU, s.QUANTITY FROM STOCK_ITEM s");
            sql.Append(" JOIN WAREHOUSE w ON w.ID = s.WAREHOUSE_ID WHERE 1 = 1");
            if (!string.IsNullOrEmpty(text))
            {
                sql.Append($" AND s.SKU LIKE '{text}%'");
            }
            if (warehouse != null)
                sql.Append(" AND w.NAME = @warehouse");
            using var cn = new SqlConnection(ConnectionString);
            return cn.Query<StockItem>(sql.ToString(), new { warehouse });
        }

        public int Restock(int threshold, string orderBy)
        {
            string query = "UPDATE STOCK_ITEM SET REORDER = 1 WHERE QUANTITY < " + threshold;
            query += " AND DISCONTINUED = 0";
            using (var cn = new SqlConnection(ConnectionString))
            using (var cmd = new SqlCommand(query, cn))
            {
                cn.Open();
                return cmd.ExecuteNonQuery();
            }
        }

        public DataTable Report(string sortColumn)
        {
            var cmd = new SqlCommand();
            cmd.CommandType = CommandType.StoredProcedure;
            cmd.CommandText = "dbo.usp_StockReport";
            var adapter = new SqlDataAdapter(string.Format("SELECT * FROM STOCK_ITEM ORDER BY {0}", sortColumn), ConnectionString);
            var table = new DataTable();
            adapter.Fill(table);
            return table;
        }
    }
}
