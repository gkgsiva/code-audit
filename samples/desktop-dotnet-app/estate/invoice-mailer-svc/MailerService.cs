using System.Messaging;
using System.Net.Mail;
using System.ServiceProcess;

namespace InvoiceMailer
{
    public class MailerService : ServiceBase
    {
        private MessageQueue _queue;

        protected override void OnStart(string[] args)
        {
            _queue = new MessageQueue(@".\private$\invoices-to-mail");
            _queue.ReceiveCompleted += (s, e) => Send((string)e.Message.Body);
            _queue.BeginReceive();
        }

        private void Send(string body)
        {
            var smtp = new SmtpClient("smtp.acme.local");
            smtp.Send("billing@acme.local", "customers@acme.local", "Your invoice", body);
        }

        static void Main()
        {
            ServiceBase.Run(new MailerService());
        }
    }
}
