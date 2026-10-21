using System.Web.Services;

namespace LegacyCrm
{
    [WebService(Namespace = "http://tempuri.org/")]
    public class ContactService : WebService
    {
        [WebMethod]
        public string GetContactName(int id)
        {
            return "unknown";
        }
    }
}
