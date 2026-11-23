package com.siddheshfulawade.payflow.contracts;

import java.util.UUID;

/**
 * Every message in the saga is a command (payment to wallet or risk) or a reply (back to payment). The set is
 * closed on purpose: {@link MessageCodec} only decodes these types, and {@link Topics#topicFor} must route each.
 */
public sealed interface Message permits Command, Reply {

    /** The payment this message belongs to. It is also the Kafka key, so one payment keeps its order. */
    UUID paymentId();
}
