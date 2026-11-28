package com.siddheshfulawade.payflow.recon.app;

import com.siddheshfulawade.payflow.recon.domain.PaymentLine;
import com.siddheshfulawade.payflow.recon.domain.WalletLine;
import java.time.LocalDate;
import java.util.List;

/** Downloads the two statements of a business date (HTTP in production, a stub in tests). */
public interface StatementSource {

    List<PaymentLine> payments(LocalDate businessDate);

    List<WalletLine> wallets(LocalDate businessDate);
}
