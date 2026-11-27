package com.siddheshfulawade.payflow.recon.app;

import com.siddheshfulawade.payflow.recon.domain.PaymentLine;
import com.siddheshfulawade.payflow.recon.domain.WalletLine;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Parses the CSV of the two statement endpoints. It is strict: the exact header, the exact column count, whole numbers
 * only. A truncated or foreign download fails here, so it can never be reported as "missing" payments.
 */
public final class StatementCsv {

    static final String PAYMENT_HEADER = "payment_id,amount,currency,state";
    static final String WALLET_HEADER = "payment_id,captured_amount,status";

    private StatementCsv() {}

    public static List<PaymentLine> payments(Reader csv) {
        return parse(
                csv, PAYMENT_HEADER, 4, c -> new PaymentLine(UUID.fromString(c[0]), Long.parseLong(c[1]), c[2], c[3]));
    }

    public static List<WalletLine> wallets(Reader csv) {
        return parse(csv, WALLET_HEADER, 3, c -> new WalletLine(UUID.fromString(c[0]), Long.parseLong(c[1]), c[2]));
    }

    private static <T> List<T> parse(Reader csv, String header, int columns, Function<String[], T> row) {
        try (BufferedReader reader = new BufferedReader(csv)) {
            if (!header.equals(reader.readLine())) {
                throw new IllegalArgumentException("unexpected statement header");
            }
            List<T> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                // limit -1 keeps empty trailing fields, so "a,b," is 3 columns, not 2
                String[] cells = line.split(",", -1);
                if (cells.length != columns) {
                    throw new IllegalArgumentException(
                            "statement row " + (lines.size() + 1) + " has " + cells.length + " columns");
                }
                lines.add(row.apply(cells));
            }
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
