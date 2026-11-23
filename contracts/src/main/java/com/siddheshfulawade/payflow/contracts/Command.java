package com.siddheshfulawade.payflow.contracts;

/** A request from the payment orchestrator to wallet or risk. Handlers must be idempotent (at-least-once). */
public sealed interface Command extends Message permits ReserveFunds, CheckRisk, CaptureFunds, ReleaseFunds {}
