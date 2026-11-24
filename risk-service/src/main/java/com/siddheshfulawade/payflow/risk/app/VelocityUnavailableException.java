package com.siddheshfulawade.payflow.risk.app;

import com.siddheshfulawade.payflow.messaging.RetriedBySenderException;

/**
 * The velocity count is unknown (cluster unreachable, failing or slow), so the payment can't be approved.
 *
 * <p>Not retried here: the check rolls back, the record is parked after one delivery, and the saga's sweeper re-sends
 * the check. The text is fixed: it ends up in the log and on the dead-letter topic, so no payer and no payment id.
 */
public class VelocityUnavailableException extends RetriedBySenderException {

    private static final long serialVersionUID = 1L;

    public VelocityUnavailableException(Throwable cause) {
        super("the velocity count is unavailable", cause);
    }
}
