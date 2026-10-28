package com.acme.teller.ui;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import javafx.fxml.FXML;

public class RatesController {
    @FXML
    private void refresh() throws Exception {
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("https://rates.acme.local/api/fx")).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    }
}
