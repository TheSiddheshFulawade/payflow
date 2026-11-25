package com.siddheshfulawade.payflow.payment.domain;

import com.siddheshfulawade.payflow.contracts.Command;

/** What to do with a payment whose step got no reply before its deadline (REQ-PF-012). */
public sealed interface Overdue {

    /** Same step, next attempt: the command again, with the payload of the first send. */
    record Resend(Command command) implements Overdue {}

    /** The step used up its re-sends: give it up and release the hold. */
    record Compensate(Transition transition) implements Overdue {}
}
