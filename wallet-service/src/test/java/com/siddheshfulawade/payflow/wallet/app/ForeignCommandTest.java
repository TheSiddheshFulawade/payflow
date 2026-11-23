package com.siddheshfulawade.payflow.wallet.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.siddheshfulawade.payflow.contracts.CheckRisk;
import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.Message;
import com.siddheshfulawade.payflow.contracts.PoisonMessageException;
import java.time.Clock;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** A valid message that does not belong on wallet.commands is poison: parked at once, never retried, no effect. */
class ForeignCommandTest {

    static Stream<Message> foreign() {
        UUID payment = UUID.randomUUID();
        return Stream.of(new CheckRisk(payment, "alice", 100, "INR"), new FundsReserved(payment));
    }

    @ParameterizedTest
    @MethodSource("foreign")
    @DisplayName("REQ-PF-021 a message that is not a wallet command is poison and touches nothing")
    void foreignMessageIsPoison(Message message) {
        Holds holds = mock(Holds.class);
        Wallets wallets = mock(Wallets.class);
        Clock clock = Clock.systemUTC();
        WalletCommandHandler handler = new WalletCommandHandler(holds, wallets, clock);

        assertThatThrownBy(() -> handler.handle(Envelope.of(message, 1, clock)))
                .isInstanceOf(PoisonMessageException.class);

        verifyNoInteractions(holds, wallets);
    }
}
