/* Nightly exchange rates: REST feed with libcurl, confirmation by SOAP (gSOAP). */
#include <curl/curl.h>
#include "soapH.h"

int main(void)
{
    CURL *curl = curl_easy_init();
    struct soap soap;
    double rate = 0;

    curl_easy_setopt(curl, CURLOPT_URL, "https://rates.acme.local/api/v1/rates/EUR");
    curl_easy_perform(curl);
    curl_easy_cleanup(curl);

    soap_init(&soap);
    soap_call_ns__confirmRate(&soap, "http://treasury.acme.local/soap/RateService", NULL,
                              "EUR", rate);
    soap_end(&soap);
    return 0;
}
