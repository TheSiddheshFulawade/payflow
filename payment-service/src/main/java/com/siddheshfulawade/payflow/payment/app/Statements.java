package com.siddheshfulawade.payflow.payment.app;

import java.time.LocalDate;
import java.util.List;

/** The payment statement: payments whose terminal state was reached on a UTC business date, ordered by payment id. */
public interface Statements {

    List<StatementLine> forDate(LocalDate businessDate);
}
