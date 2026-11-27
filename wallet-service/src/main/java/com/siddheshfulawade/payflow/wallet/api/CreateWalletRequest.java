package com.siddheshfulawade.payflow.wallet.api;

import com.siddheshfulawade.payflow.contracts.Subjects;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Seeds a wallet.
 *
 * @param id same alphabet as the payer and payee of the wallet commands, so every wallet can be paid
 * @param ownerSub the owner's JWT subject: the one rule of {@link Subjects}: 1 to 64 printable ASCII characters
 * @param initialBalance minor units, at most 10 billion rupees
 */
record CreateWalletRequest(
        @NotNull @Pattern(regexp = CreateWalletRequest.WALLET_ID)
        String id,

        @NotNull @Pattern(regexp = Subjects.PATTERN) String ownerSub,
        @NotNull @Min(0) @Max(1_000_000_000_000L) Long initialBalance) {

    static final String WALLET_ID = "[A-Za-z0-9][A-Za-z0-9._@-]{0,63}";
}
