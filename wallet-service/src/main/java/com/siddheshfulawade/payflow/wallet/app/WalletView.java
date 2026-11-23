package com.siddheshfulawade.payflow.wallet.app;

import com.siddheshfulawade.payflow.wallet.domain.WalletStatus;

/** What the API shows of a wallet. Amounts are minor units. */
public record WalletView(String id, String ownerSub, long available, long held, WalletStatus status) {}
