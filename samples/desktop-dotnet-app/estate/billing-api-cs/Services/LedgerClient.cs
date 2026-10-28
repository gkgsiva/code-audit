namespace BillingApi.Services;

public class LedgerClient
{
    private static readonly HttpClient Http = new HttpClient { BaseAddress = new Uri("https://ledger.acme.local/") };

    public Task<HttpResponseMessage> Post(string body) =>
        Http.PostAsync("api/postings", new StringContent(body));
}
