package com.siddheshfulawade.payflow.contracts;

/**
 * A message that can never be processed (malformed, unknown type, missing field, unsupported version). Retrying it
 * cannot help, so consumers park it on the dead-letter topic at once.
 *
 * <p>The message is log-safe by construction: a fixed reason plus the JSON path of the bad field, never a value from
 * the document (it may hold account ids, or a forged log line) and never the cause's message. The cause is kept for
 * debugging, which is why the dead-letter topic does not carry the stack trace header.
 */
public final class PoisonMessageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PoisonMessageException(String message) {
        super(message);
    }

    public PoisonMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
