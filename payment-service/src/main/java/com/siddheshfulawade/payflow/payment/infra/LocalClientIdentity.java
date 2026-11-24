package com.siddheshfulawade.payflow.payment.infra;

import com.siddheshfulawade.payflow.payment.app.ClientIdentity;
import org.springframework.stereotype.Component;

/**
 * One fixed client until PF-07, which replaces it with the JWT subject. Acceptable only because the service listens on
 * loopback until then. Never from a header: a caller could then read and block another client's keys.
 */
@Component
class LocalClientIdentity implements ClientIdentity {

    @Override
    public String clientId() {
        return "local";
    }
}
