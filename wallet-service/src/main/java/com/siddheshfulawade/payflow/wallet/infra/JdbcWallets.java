package com.siddheshfulawade.payflow.wallet.infra;

import com.siddheshfulawade.payflow.wallet.app.WalletView;
import com.siddheshfulawade.payflow.wallet.app.Wallets;
import com.siddheshfulawade.payflow.wallet.domain.Wallet;
import com.siddheshfulawade.payflow.wallet.domain.WalletStatus;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Balances change only by relative updates ("held = held + amount"), never by writing a value that was read before,
 * so an update can't overwrite another one, and payer and payee may be the same row.
 *
 * <p>Each movement's guard ("and available >= amount") repeats the decision in SQL: if the code were wrong, 0 rows
 * change and the transaction rolls back. The table's CHECKs are the third guard.
 */
@Repository
class JdbcWallets implements Wallets {

    private static final String SEED = "SEED";
    private static final String RESERVE = "RESERVE";
    private static final String RELEASE = "RELEASE";
    private static final String CAPTURE_DEBIT = "CAPTURE_DEBIT";
    private static final String CAPTURE_CREDIT = "CAPTURE_CREDIT";

    private final JdbcClient jdbc;

    JdbcWallets(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Wallet> lock(Collection<String> ids) {
        // order by: rows are locked in the order they are returned, so every transaction locks in the same order
        return jdbc
                .sql("select id, available, status from wallet where id in (:ids) order by id for update")
                .param("ids", ids)
                .query((rs, n) -> new Wallet(
                        rs.getString("id"), rs.getLong("available"), WalletStatus.valueOf(rs.getString("status"))))
                .list()
                .stream()
                .collect(Collectors.toMap(Wallet::id, Function.identity()));
    }

    @Override
    public String ownerOf(String id) {
        return jdbc.sql("select owner_sub from wallet where id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    @Override
    public void reserve(UUID paymentId, String payer, long amount) {
        move(RESERVE, paymentId, payer, amount, """
                update wallet set available = available - :amount, held = held + :amount, version = version + 1
                where id = :id and available >= :amount""");
    }

    @Override
    public void release(UUID paymentId, String payer, long amount) {
        move(RELEASE, paymentId, payer, amount, """
                update wallet set held = held - :amount, available = available + :amount, version = version + 1
                where id = :id and held >= :amount""");
    }

    @Override
    public void capture(UUID paymentId, String payer, String payee, long amount) {
        // both legs in the caller's transaction: available + held over all wallets stays the same (REQ-PF-030)
        move(CAPTURE_DEBIT, paymentId, payer, amount, """
                update wallet set held = held - :amount, version = version + 1
                where id = :id and held >= :amount""");
        move(CAPTURE_CREDIT, paymentId, payee, amount, """
                update wallet set available = available + :amount, version = version + 1
                where id = :id""");
    }

    /** One balance change and its ledger entry. */
    private void move(String kind, UUID paymentId, String walletId, long amount, String update) {
        int updated =
                jdbc.sql(update).param("amount", amount).param("id", walletId).update();
        Updates.exactlyOne(updated, kind + " of payment " + paymentId);
        ledger(kind, paymentId, walletId, amount);
    }

    private void ledger(String kind, UUID paymentId, String walletId, long amount) {
        jdbc.sql("""
                        insert into ledger_entry(id, wallet_id, payment_id, kind, amount)
                        values (:id, :wallet, :payment, :kind, :amount)""")
                .param("id", UUID.randomUUID())
                .param("wallet", walletId)
                .param("payment", paymentId)
                .param("kind", kind)
                .param("amount", amount)
                .update();
    }

    @Override
    public boolean create(String id, String ownerSub, long initialBalance) {
        int inserted = jdbc.sql("""
                        insert into wallet(id, owner_sub, available, held, status)
                        values (:id, :owner, :available, 0, 'ACTIVE')
                        on conflict (id) do nothing""")
                .param("id", id)
                .param("owner", ownerSub)
                .param("available", initialBalance)
                .update();
        if (inserted == 0) {
            return false;
        }
        ledger(SEED, null, id, initialBalance);
        return true;
    }

    @Override
    public boolean changeStatus(String id, WalletStatus status) {
        return jdbc.sql("update wallet set status = :status, version = version + 1 where id = :id")
                        .param("status", status.name())
                        .param("id", id)
                        .update()
                == 1;
    }

    @Override
    public Optional<WalletView> find(String id) {
        return jdbc.sql("select id, owner_sub, available, held, status from wallet where id = :id")
                .param("id", id)
                .query((rs, n) -> new WalletView(
                        rs.getString("id"),
                        rs.getString("owner_sub"),
                        rs.getLong("available"),
                        rs.getLong("held"),
                        WalletStatus.valueOf(rs.getString("status"))))
                .optional();
    }
}
