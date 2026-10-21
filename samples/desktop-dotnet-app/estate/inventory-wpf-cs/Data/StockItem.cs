using System.ComponentModel.DataAnnotations.Schema;

namespace InventoryApp.Data
{
    [Table("STOCK_ITEM")]
    public class StockItem
    {
        public int Id { get; set; }
        public string Sku { get; set; }
        public int Quantity { get; set; }
    }
}
