package com.siddheshfulawade.payflow.wallet.api;

import com.siddheshfulawade.payflow.wallet.app.WalletAdminService;
import com.siddheshfulawade.payflow.wallet.app.WalletNotFoundException;
import com.siddheshfulawade.payflow.wallet.app.WalletView;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reading a wallet: the owner reads their own, ops read any. */
@RestController
@RequestMapping("/api/v1/wallets")
class WalletController {

    private final WalletAdminService wallets;

    WalletController(WalletAdminService wallets) {
        this.wallets = wallets;
    }

    /**
     * Invariant (REQ-PF-003): a customer reads only the wallet they own (the token subject is the wallet's
     * {@code ownerSub}); only {@code ops} reads any. Someone else's wallet is the same 404 as an unknown id, so the
     * response does not tell whether the id exists.
     */
    @GetMapping("/{id}")
    WalletView read(@PathVariable @Pattern(regexp = CreateWalletRequest.WALLET_ID) String id, Authentication caller) {
        return wallets.find(id)
                .filter(wallet -> mayRead(caller, wallet))
                .orElseThrow(() -> new WalletNotFoundException(id));
    }

    private static boolean mayRead(Authentication caller, WalletView wallet) {
        boolean ops = caller.getAuthorities().stream()
                .anyMatch(role -> role.getAuthority().equals(SecurityConfig.OPS));
        return ops || wallet.ownerSub().equals(caller.getName());
    }
}
