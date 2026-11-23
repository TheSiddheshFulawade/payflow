package com.siddheshfulawade.payflow.wallet.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.FundsCaptured;
import com.siddheshfulawade.payflow.wallet.support.WalletIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** payment-service refuses payer = payee (REQ-PF-004), but the wallet must not lose money if one gets through. */
class CaptureToSelfTest extends WalletIntegrationTest {

    @Test
    @DisplayName("REQ-PF-030 a capture to the payer itself only moves held back to available")
    void payerIsPayee() {
        UUID payment = UUID.randomUUID();
        wallet("alice", 1_000);
        apply(reserve(payment, "alice", 300));

        assertThat(apply(capture(payment, "alice", "alice", 300)).payload()).isEqualTo(new FundsCaptured(payment));

        assertBalances("alice", 1_000, 0);
        assertThat(holdState(payment)).isEqualTo("CAPTURED");
    }
}
