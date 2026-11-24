package com.siddheshfulawade.payflow.payment.domain;

import com.siddheshfulawade.payflow.contracts.Command;
import java.util.Optional;

/**
 * One move of the saga.
 *
 * @param command what to send next; empty when the payment ends
 * @param reason why the payment failed; null for a successful step
 */
public record Transition(PaymentState to, Optional<Command> command, String reason) {

    static Transition step(PaymentState to, Command command) {
        return new Transition(to, Optional.of(command), null);
    }

    static Transition end(PaymentState to, String reason) {
        return new Transition(to, Optional.empty(), reason);
    }
}
