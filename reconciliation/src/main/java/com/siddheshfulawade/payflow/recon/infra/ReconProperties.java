package com.siddheshfulawade.payflow.recon.infra;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Where the statements come from. {@code opsToken} is a bearer token of the ops role; it is never logged and has no
 * default (a job without it would only download 401 pages, so the start fails). {@code maxStatementBytes} caps one
 * download; {@code currency} is the only currency a statement line may have.
 */
@Validated
@ConfigurationProperties("payflow.recon")
record ReconProperties(
        String paymentUrl,
        String walletUrl,
        @NotBlank String opsToken,
        // at most the largest array the JVM allocates (Integer.MAX_VALUE - 9): above it, cap + 1 overflows or the
        // body is cut short without an error
        @DefaultValue("67108864") @Positive @Max(Integer.MAX_VALUE - 9)
        long maxStatementBytes,

        @DefaultValue("INR") String currency) {

    @Override
    public String toString() {
        return "ReconProperties[paymentUrl=" + paymentUrl + ", walletUrl=" + walletUrl + ", opsToken=***]";
    }
}
