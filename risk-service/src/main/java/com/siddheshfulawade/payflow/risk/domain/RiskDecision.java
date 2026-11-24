package com.siddheshfulawade.payflow.risk.domain;

/**
 * The recorded decision for one payment. Invariant: it is made once and is permanent, so a re-sent check is answered
 * from here and never from today's totals.
 *
 * @param rule the rule that refused the payment; null unless REJECTED
 */
public record RiskDecision(String payer, long amount, Verdict verdict, RiskRule rule) {

    /** Whether the decision was made for exactly this money. */
    public boolean isFor(String checkedPayer, long checkedAmount) {
        return payer.equals(checkedPayer) && amount == checkedAmount;
    }
}
