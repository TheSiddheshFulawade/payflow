package com.siddheshfulawade.payflow.risk.app;

import java.time.LocalDate;

/** The approved amounts per payer and UTC date. Every method joins the caller's transaction. */
public interface DailyTotals {

    /** The payer's total of that date, row-locked until the transaction ends (0 if nothing was approved yet). */
    long lock(String payer, LocalDate date);

    /** Adds an approved amount; throws if the total would cross {@code limit}. */
    void add(String payer, LocalDate date, long amount, long limit);
}
