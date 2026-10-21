using Microsoft.AspNetCore.Mvc;

namespace BillingApi.Controllers;

public class HomeController : Controller
{
    public IActionResult Index() => View();
}
