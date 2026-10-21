using System.Windows;
using InventoryApp.Data;
using InventoryApp.Views;

namespace InventoryApp
{
    public partial class MainWindow : Window
    {
        private readonly StockRepository _repo = new StockRepository();

        public MainWindow()
        {
            InitializeComponent();
        }

        private void Search_Click(object sender, RoutedEventArgs e)
        {
            Results.ItemsSource = _repo.Find(SearchBox.Text, WarehouseList.Text);
            if (Results.Items.Count == 0)
            {
                var dialog = new StockDialog();
                dialog.ShowDialog();
            }
        }
    }
}
