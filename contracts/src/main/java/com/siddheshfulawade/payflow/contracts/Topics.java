package com.siddheshfulawade.payflow.contracts;

/** Kafka topic names. Every record is keyed by {@code paymentId}, so one payment always lands on one partition. */
public final class Topics {

    public static final String WALLET_COMMANDS = "wallet.commands";
    public static final String WALLET_REPLIES = "wallet.replies";
    public static final String RISK_COMMANDS = "risk.commands";
    public static final String RISK_REPLIES = "risk.replies";

    /** Partitions per topic, dead-letter topics included. */
    public static final int PARTITIONS = 3;

    private static final String DEAD_LETTER_SUFFIX = ".dlt";

    /** Where records that can never be processed are parked. */
    public static String deadLetter(String topic) {
        return topic + DEAD_LETTER_SUFFIX;
    }

    /** The topic a message goes to. Exhaustive over the sealed types: a new message type does not compile unrouted. */
    public static String topicFor(Message message) {
        return switch (message) {
            case ReserveFunds m -> WALLET_COMMANDS;
            case CaptureFunds m -> WALLET_COMMANDS;
            case ReleaseFunds m -> WALLET_COMMANDS;
            case CheckRisk m -> RISK_COMMANDS;
            case FundsReserved m -> WALLET_REPLIES;
            case FundsRejected m -> WALLET_REPLIES;
            case FundsCaptured m -> WALLET_REPLIES;
            case CaptureFailed m -> WALLET_REPLIES;
            case FundsReleased m -> WALLET_REPLIES;
            case RiskApproved m -> RISK_REPLIES;
            case RiskRejected m -> RISK_REPLIES;
        };
    }

    private Topics() {}
}
