package com.siddheshfulawade.payflow.wallet.app;

import com.siddheshfulawade.payflow.wallet.domain.Wallet;
import com.siddheshfulawade.payflow.wallet.domain.WalletStatus;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The wallet rows and their ledger. Every method joins the caller's transaction.
 *
 * <p>Invariant: each movement writes its ledger entries together with the balances, so the ledger explains every
 * balance. A movement that does not change exactly one row per update throws, which rolls everything back.
 */
public interface Wallets {

    /** Row-locks the wallets that exist among {@code ids}, in id order so that two callers cannot deadlock. */
    Map<String, Wallet> lock(Collection<String> ids);

    /** Available to held. */
    void reserve(UUID paymentId, String payer, long amount);

    /** Held back to available. */
    void release(UUID paymentId, String payer, long amount);

    /** The payer's held to the payee's available. Payer and payee may be the same wallet. */
    void capture(UUID paymentId, String payer, String payee, long amount);

    /** Creates an ACTIVE wallet and its SEED entry; false (and no change) if the id exists. */
    boolean create(String id, String ownerSub, long initialBalance);

    /** False if there is no such wallet. */
    boolean changeStatus(String id, WalletStatus status);

    Optional<WalletView> find(String id);
}
