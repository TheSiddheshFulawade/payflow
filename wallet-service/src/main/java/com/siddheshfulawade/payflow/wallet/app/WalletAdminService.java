package com.siddheshfulawade.payflow.wallet.app;

import com.siddheshfulawade.payflow.wallet.domain.WalletStatus;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ops use cases: seed a wallet, change its status, read it. */
@Service
@Transactional
public class WalletAdminService {

    private final Wallets wallets;

    public WalletAdminService(Wallets wallets) {
        this.wallets = wallets;
    }

    /**
     * Creates a wallet with money from outside: the only change of the total balance, recorded as a SEED ledger entry
     * in the same transaction (REQ-PF-030). The id makes a retry safe: a second seed changes nothing.
     */
    public void seed(String id, String ownerSub, long initialBalance) {
        if (!wallets.create(id, ownerSub, initialBalance)) {
            throw new WalletAlreadyExistsException(id);
        }
    }

    /** Waits for running movements of this wallet (row lock), so a reserve sees the old or the new status. */
    public void changeStatus(String id, WalletStatus status) {
        if (!wallets.changeStatus(id, status)) {
            throw new WalletNotFoundException(id);
        }
    }

    @Transactional(readOnly = true)
    public Optional<WalletView> find(String id) {
        return wallets.find(id);
    }
}
