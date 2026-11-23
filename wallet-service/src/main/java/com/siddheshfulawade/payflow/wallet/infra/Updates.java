package com.siddheshfulawade.payflow.wallet.infra;

final class Updates {

    /** A money update that did not hit exactly one row is a bug: throw, so the whole transaction rolls back. */
    static void exactlyOne(int rows, String what) {
        if (rows != 1) {
            throw new IllegalStateException("expected to update 1 row but updated " + rows + ": " + what);
        }
    }

    private Updates() {}
}
