package com.siddheshfulawade.payflow.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.CaptureFailed;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The words the database, the API and the reply messages share: a rename here is a breaking change. */
class DomainVocabularyTest {

    @Test
    @DisplayName("REQ-PF-030 wallet statuses are exactly ACTIVE, FROZEN, CLOSED (the wallet.status CHECK values)")
    void walletStatuses() {
        assertThat(Arrays.stream(WalletStatus.values()).map(Enum::name))
                .containsExactlyInAnyOrder("ACTIVE", "FROZEN", "CLOSED");
    }

    @Test
    @DisplayName("REQ-PF-030 hold states are exactly RESERVED, RELEASED, CAPTURED, REJECTED")
    void holdStates() {
        assertThat(Arrays.stream(HoldState.values()).map(Enum::name))
                .containsExactlyInAnyOrder("RESERVED", "RELEASED", "CAPTURED", "REJECTED");
    }

    @Test
    @DisplayName("REQ-PF-030 reject reasons are exactly the six documented ones (a mismatch is poison, not a reason)")
    void rejectReasons() {
        assertThat(Arrays.stream(RejectReason.values()).map(Enum::name))
                .containsExactlyInAnyOrder(
                        "WALLET_UNKNOWN",
                        "WALLET_FROZEN",
                        "INSUFFICIENT_FUNDS",
                        "PAYEE_UNKNOWN",
                        "PAYEE_NOT_ACTIVE",
                        "NO_ACTIVE_HOLD");
    }

    @ParameterizedTest
    @EnumSource(RejectReason.class)
    @DisplayName("REQ-PF-030 every reason name is a valid reason text of both failure replies")
    void reasonFitsTheReplyContracts(RejectReason reason) {
        UUID payment = UUID.randomUUID();
        assertThat(new FundsRejected(payment, reason.name()).reason()).isEqualTo(reason.name());
        assertThat(new CaptureFailed(payment, reason.name()).reason()).isEqualTo(reason.name());
    }
}
