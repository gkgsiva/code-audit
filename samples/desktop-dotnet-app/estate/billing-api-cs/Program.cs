using Hangfire;
using BillingApi.Jobs;

var builder = WebApplication.CreateBuilder(args);
builder.Services.AddControllersWithViews();
builder.Services.AddHangfire(c => c.UseSqlServerStorage(builder.Configuration.GetConnectionString("Billing")));
var app = builder.Build();
app.MapControllers();
RecurringJob.AddOrUpdate<InvoiceJobs>("close-invoices", j => j.CloseOverdue(), Cron.Daily(2));
app.Run();
