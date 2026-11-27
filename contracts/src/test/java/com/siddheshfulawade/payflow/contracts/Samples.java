package com.siddheshfulawade.payflow.contracts;

import java.util.List;
import java.util.UUID;

/** One instance of each of the 11 message types. */
final class Samples {

    static final UUID PAYMENT = UUID.fromString("11111111-2222-3333-4444-555555555555");

    static List<Message> all() {
        return List.of(
                new ReserveFunds(PAYMENT, "alice", 1_250, "INR", "REQ"),
                new CheckRisk(PAYMENT, "alice", 1_250, "INR"),
                new CaptureFunds(PAYMENT, "alice", "bob", 1_250, "INR"),
                new ReleaseFunds(PAYMENT, "alice", 1_250),
                new FundsReserved(PAYMENT),
                new FundsRejected(PAYMENT, "insufficient"),
                new RiskApproved(PAYMENT),
                new RiskRejected(PAYMENT, "velocity"),
                new FundsCaptured(PAYMENT),
                new CaptureFailed(PAYMENT, "payee frozen"),
                new FundsReleased(PAYMENT));
    }

    private Samples() {}
}
