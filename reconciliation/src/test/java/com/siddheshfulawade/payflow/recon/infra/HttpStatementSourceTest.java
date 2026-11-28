package com.siddheshfulawade.payflow.recon.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.recon.domain.PaymentLine;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-PF-040: the download refuses a response above the configured size, whatever way the server announces it. The JDK's
 * own in-process HTTP server on the loopback address stands in for the payment service (no Docker, no Spring).
 */
class HttpStatementSourceTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-26");
    private static final long CAP = 1_000;
    private static final String HEADER = "payment_id,amount,currency,state\n";

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpStatementSource source() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new HttpStatementSource(new ReconProperties(url, url, "ops-token", CAP, "INR"));
    }

    /** Serves the body; fixed = announce Content-Length, otherwise chunked (length unknown up front). */
    private void serve(String body, boolean fixed) {
        server.createContext("/api/v1/ops/statements", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, fixed ? bytes.length : 0);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private static String rows(int n) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 0; i < n; i++) {
            csv.append(new UUID(0, i)).append(",5,INR,COMPLETED\n");
        }
        return csv.toString();
    }

    @Test
    @DisplayName("REQ-PF-040 a statement within the cap is read, with the ops bearer token")
    void withinCap() {
        serve(rows(5), true);

        List<PaymentLine> lines = source().payments(DAY);

        assertThat(lines).hasSize(5);
        assertThat(authorization.get()).isEqualTo("Bearer ops-token");
    }

    @Test
    @DisplayName("REQ-PF-040 a response announced larger than the cap is refused with a clear error")
    void announcedTooLarge() {
        serve(rows(100), true);

        assertThatThrownBy(() -> source().payments(DAY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds")
                .hasMessageContaining(String.valueOf(CAP));
    }

    @Test
    @DisplayName("REQ-PF-040 a chunked response that grows past the cap is refused too (no Content-Length to trust)")
    void chunkedTooLarge() {
        serve(rows(100), false);

        assertThatThrownBy(() -> source().wallets(DAY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds")
                .hasMessageContaining(String.valueOf(CAP));
    }
}
