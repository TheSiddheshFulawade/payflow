package com.siddheshfulawade.payflow.recon.infra;

import com.siddheshfulawade.payflow.recon.app.StatementCsv;
import com.siddheshfulawade.payflow.recon.app.StatementSource;
import com.siddheshfulawade.payflow.recon.domain.PaymentLine;
import com.siddheshfulawade.payflow.recon.domain.WalletLine;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("statement download answered HTTP " + response.statusCode());
                }
                return readWithinCap(response, body);
            }
        } catch (IOException e) {
            throw new IllegalStateException("statement download failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("statement download interrupted", e);
        }
    }

    /**
     * Invariant: memory use of a download is bounded by {@code maxStatementBytes}. An announced length over the cap is
     * refused before reading; with no length (chunked) the read stops one byte past the cap.
     */
    private String readWithinCap(HttpResponse<InputStream> response, InputStream body) throws IOException {
        long cap = properties.maxStatementBytes();
        long announced = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (announced > cap) {
            throw tooLarge(cap);
        }
        byte[] bytes = body.readNBytes((int) Math.min(cap + 1, Integer.MAX_VALUE - 8));
        if (bytes.length > cap) {
            throw tooLarge(cap);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static IllegalStateException tooLarge(long cap) {
        return new IllegalStateException("statement exceeds the limit of " + cap + " bytes");
    }
}
