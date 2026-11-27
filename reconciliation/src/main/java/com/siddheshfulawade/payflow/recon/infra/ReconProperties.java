package com.siddheshfulawade.payflow.recon.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where the statements come from. {@code opsToken} is a bearer token of the ops role; it is never logged. */
@ConfigurationProperties("payflow.recon")
record ReconProperties(String paymentUrl, String walletUrl, String opsToken) {

    @Override
    public String toString() {
        return "ReconProperties[paymentUrl=" + paymentUrl + ", walletUrl=" + walletUrl + ", opsToken=***]";
    }
}
