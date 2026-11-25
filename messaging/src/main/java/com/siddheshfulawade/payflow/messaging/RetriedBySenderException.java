package com.siddheshfulawade.payflow.messaging;

/**
 * A handler failure that the sender repairs: the saga's sweeper re-sends the command when the step's deadline passes.
 *
 * <p>The error handler does not retry it: one delivery, then the dead-letter topic. Retrying here would hold the
 * listener thread, which serves all partitions of the instance, past the step's deadline: for this payment and for
 * every payment behind it. Their re-sends would queue up behind the blocked record too.
 *
 * <p>Throw it only from a handler that a sender re-sends to with a new message id, and whose transaction rolls back: the
 * inbox row must not stay, or a redelivery of the parked record would look like a duplicate.
 *
 * <p>The message ends up in the log and on the dead-letter topic: fixed text, no values from the record.
 */
public class RetriedBySenderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RetriedBySenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
