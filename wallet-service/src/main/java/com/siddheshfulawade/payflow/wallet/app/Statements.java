package com.siddheshfulawade.payflow.wallet.app;

import java.time.LocalDate;
import java.util.List;

/** The wallet statement: payments whose hold was settled (captured, released, rejected) on a UTC date, by id. */
public interface Statements {

    List<StatementLine> forDate(LocalDate businessDate);
}
