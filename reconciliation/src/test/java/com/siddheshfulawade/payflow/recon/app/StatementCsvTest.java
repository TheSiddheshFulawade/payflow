package com.siddheshfulawade.payflow.recon.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.recon.domain.PaymentLine;
import com.siddheshfulawade.payflow.recon.domain.WalletLine;
import java.io.StringReader;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The CSV the two ops endpoints produce (header row, long minor units). */
class StatementCsvTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Test
    @DisplayName("REQ-PF-040 payment CSV: header skipped, CRLF or LF, Long.MAX_VALUE kept exact")
    void payments() {
        String csv = "payment_id,amount,currency,state\r\n" + A + "," + Long.MAX_VALUE + ",INR,COMPLETED\n";

        assertThat(StatementCsv.payments(new StringReader(csv)))
                .containsExactly(new PaymentLine(A, Long.MAX_VALUE, "INR", "COMPLETED"));
    }

    @Test
    @DisplayName("REQ-PF-040 wallet CSV: header skipped, captured amount and status read")
    void wallets() {
        String csv = "payment_id,captured_amount,status\n" + A + ",0,RELEASED\n";

        assertThat(StatementCsv.wallets(new StringReader(csv))).containsExactly(new WalletLine(A, 0, "RELEASED"));
    }

    @Test
    @DisplayName("REQ-PF-040 a header-only CSV is an empty statement")
    void headerOnly() {
        assertThat(StatementCsv.payments(new StringReader("payment_id,amount,currency,state\n")))
                .isEmpty();
        assertThat(StatementCsv.wallets(new StringReader("payment_id,captured_amount,status\n")))
                .isEmpty();
    }

    @Test
    @DisplayName(
            "REQ-PF-040 a wrong header, a bad number, a short row or an empty body is refused (truncated download)")
    void malformed() {
        assertThatThrownBy(() -> StatementCsv.payments(new StringReader("a,b\n" + A + ",5,INR,COMPLETED\n")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StatementCsv.payments(
                        new StringReader("payment_id,amount,currency,state\n" + A + ",12.5,INR,COMPLETED\n")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        StatementCsv.wallets(new StringReader("payment_id,captured_amount,status\n" + A + ",5\n")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StatementCsv.payments(new StringReader("")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
