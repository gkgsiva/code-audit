using System;
using System.Windows;
using InventoryApp.Data;

namespace InventoryApp.Views
{
    public partial class StockDialog : Window
    {
        public StockDialog()
        {
            InitializeComponent();
        }

        private void Save_Click(object sender, RoutedEventArgs e)
        {
            using var db = new InventoryContext();
            var item = db.StockItems.FirstOrDefault(i => i.Sku == Sku.Text);
            if (item == null)
            {
                db.StockItems.Add(new StockItem { Sku = Sku.Text, Quantity = int.Parse(Quantity.Text) });
            }
            db.SaveChanges();
            Close();
        }
    }
}
