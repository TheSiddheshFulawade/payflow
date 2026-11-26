package com.siddheshfulawade.payflow.risk.support;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback ports for test members. Below the ephemeral ranges of Linux (from 32768) and Windows (from 49152), so no
 * outgoing connection can take a port between our probe and the member's bind, and no port is handed out twice in a
 * test run. The random start keeps two test JVMs on one machine apart.
 */
public final class Ports {

    private static final int FIRST = 20_000;
    private static final int LAST = 31_999;

    private static final AtomicInteger NEXT =
            new AtomicInteger(ThreadLocalRandom.current().nextInt(FIRST, LAST - 2_000));

    public static int free() {
        for (int port = NEXT.getAndIncrement(); port <= LAST; port = NEXT.getAndIncrement()) {
            try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                return probe.getLocalPort();
            } catch (IOException taken) {
                // another process listens there: try the next one
            }
        }
        throw new IllegalStateException("no free test port up to " + LAST);
    }

    private Ports() {}
}
