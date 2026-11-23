package com.siddheshfulawade.payflow.contracts;

/** The answer of wallet or risk to a command, sent back to the payment orchestrator. */
public sealed interface Reply extends Message
        permits FundsReserved, FundsRejected, RiskApproved, RiskRejected, FundsCaptured, CaptureFailed, FundsReleased {}
