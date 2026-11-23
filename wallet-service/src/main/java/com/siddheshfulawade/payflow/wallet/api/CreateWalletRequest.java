package com.siddheshfulawade.payflow.wallet.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Seeds a wallet.
 *
 * @param id same alphabet as the payer and payee of the wallet commands, so every wallet can be paid
 * @param ownerSub the owner's JWT subject: printable ASCII, at most 255 chars (OpenID Connect's limit)
 * @param initialBalance minor units, at most 10 billion rupees
 */
record CreateWalletRequest(
        @NotNull @Pattern(regexp = CreateWalletRequest.WALLET_ID)
        String id,

        @NotNull @Pattern(regexp = "[\\x21-\\x7E]{1,255}") String ownerSub,
        @NotNull @Min(0) @Max(1_000_000_000_000L) Long initialBalance) {

    static final String WALLET_ID = "[A-Za-z0-9][A-Za-z0-9._@-]{0,63}";
}
