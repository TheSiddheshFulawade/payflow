package com.siddheshfulawade.payflow.messaging.support;

import com.siddheshfulawade.payflow.contracts.CheckRisk;
import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import java.time.Clock;
import java.util.UUID;

public final class Fixtures {

    public static Envelope reserve(UUID paymentId) {
        return Envelope.of(new ReserveFunds(paymentId, "alice", 500, "INR", "REQ"), 1, Clock.systemUTC());
    }

    /** Goes to risk.commands, which no test listener consumes. */
    public static Envelope risk(UUID paymentId) {
        return Envelope.of(new CheckRisk(paymentId, "alice", 500, "INR"), 1, Clock.systemUTC());
    }

    private Fixtures() {}
}
