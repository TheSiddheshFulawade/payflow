package com.siddheshfulawade.payflow.wallet.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.wallet.support.WalletIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Settings every pooled connection must carry. */
class WalletConnectionTest extends WalletIntegrationTest {

    @Test
    @DisplayName("REQ-PF-030 a pooled connection has lock_timeout 5s")
    void pooledConnectionHasLockTimeout() {
        assertThat(jdbc.queryForObject("show lock_timeout", String.class)).isEqualTo("5s");
    }
}
