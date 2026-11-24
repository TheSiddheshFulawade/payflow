package com.siddheshfulawade.payflow.risk.infra;

final class Updates {

    /** An update that did not hit exactly one row is a bug: throw, so the whole transaction rolls back. */
    static void exactlyOne(int rows, String what) {
        if (rows != 1) {
            throw new IllegalStateException("expected to update 1 row but updated " + rows + ": " + what);
        }
    }

    private Updates() {}
}
