package com.siddheshfulawade.payflow.recon.infra;

import com.siddheshfulawade.payflow.recon.app.StatementCsv;
import com.siddheshfulawade.payflow.recon.app.StatementSource;
import com.siddheshfulawade.payflow.recon.domain.PaymentLine;
import com.siddheshfulawade.payflow.recon.domain.WalletLine;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Downloads the statements from the two services' ops endpoints. Anything but a 200 fails the download (and so the
 * job): an error page or an empty answer must never be read as "no payments that day".
 */
@Component
class HttpStatementSource implements StatementSource {

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ReconProperties properties;

    HttpStatementSource(ReconProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<PaymentLine> payments(LocalDate businessDate) {
        return StatementCsv.payments(new StringReader(get(properties.paymentUrl(), businessDate)));
    }

    @Override
    public List<WalletLine> wallets(LocalDate businessDate) {
        return StatementCsv.wallets(new StringReader(get(properties.walletUrl(), businessDate)));
    }

    private String get(String baseUrl, LocalDate businessDate) {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/api/v1/ops/statements?date=" + businessDate))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + properties.opsToken())
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("statement download answered HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("statement download failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("statement download interrupted", e);
        }
    }
}
