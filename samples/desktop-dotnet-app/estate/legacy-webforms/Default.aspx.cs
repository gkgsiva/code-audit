using System;
using System.Data;
using System.Data.OleDb;
using System.Web.UI;

namespace LegacyCrm
{
    public partial class DefaultPage : Page
    {
        protected void btnSearch_Click(object sender, EventArgs e)
        {
            string sql = "SELECT CONTACT_ID, NAME FROM CRM_CONTACT WHERE REGION_ID = " + ddlRegion.SelectedValue;
            if (txtSearch.Text.Length > 0)
                sql += " AND NAME LIKE '" + txtSearch.Text + "%'";
            var adapter = new OleDbDataAdapter(sql, System.Configuration.ConfigurationManager.ConnectionStrings["Crm"].ConnectionString);
            var table = new DataTable();
            adapter.Fill(table);
            gvContacts.DataSource = table;
            gvContacts.DataBind();
        }
    }
}
