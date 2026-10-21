package com.acme.quote;

/** Insurance quotes, published by Axis through server-config.wsdd. */
public class QuoteService {

    public double getQuote(String product, int years) {
        return 120.0 * years;
    }

    public String[] listQuotes(String customerId) {
        return new String[0];
    }
}
