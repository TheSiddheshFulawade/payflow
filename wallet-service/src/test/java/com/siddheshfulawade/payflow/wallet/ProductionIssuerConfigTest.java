package com.siddheshfulawade.payflow.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The production application.yml must not trust a made-up issuer when the operator forgets to name the real one. */
class ProductionIssuerConfigTest {

    @Test
    @DisplayName("REQ-PF-003 issuer-uri is ${PAYFLOW_ISSUER_URI} with no default: a missing value fails at start-up")
    void issuerHasNoDefault() throws IOException {
        List<String> lines;
        try (InputStream yml = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(yml).as("the production application.yml").isNotNull();
            lines = new String(yml.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .toList();
        }

        List<String> issuer = lines.stream()
                .filter(line -> line.trim().startsWith("issuer-uri:"))
                .toList();

        assertThat(issuer).hasSize(1);
        assertThat(issuer.get(0).trim()).isEqualTo("issuer-uri: ${PAYFLOW_ISSUER_URI}");
    }
}
