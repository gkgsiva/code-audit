using Microsoft.EntityFrameworkCore;

namespace InventoryApp.Data
{
    public class InventoryContext : DbContext
    {
        public DbSet<StockItem> StockItems { get; set; }

        protected override void OnConfiguring(DbContextOptionsBuilder options)
        {
            options.UseSqlServer("Server=INVSRV01;Database=INVENTORY;User Id=inv_app;Password=Inv#2019");
        }
    }
}
