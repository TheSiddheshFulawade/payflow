package com.siddheshfulawade.payflow.recon.app;

import com.siddheshfulawade.payflow.recon.domain.PaymentLine;
import com.siddheshfulawade.payflow.recon.domain.WalletLine;
import java.time.LocalDate;
import java.util.List;

/** recon_staging. Staging a line twice (same date and payment id) must leave one row. Reads are ordered by id. */
public interface StagingStore {

    /** Deletes the date's staging rows, both tables. Never touches the report. */
    void clearStaging(LocalDate businessDate);

    void stagePayments(LocalDate businessDate, List<PaymentLine> chunk);

    void stageWallets(LocalDate businessDate, List<WalletLine> chunk);

    List<PaymentLine> stagedPayments(LocalDate businessDate);

    List<WalletLine> stagedWallets(LocalDate businessDate);
}
