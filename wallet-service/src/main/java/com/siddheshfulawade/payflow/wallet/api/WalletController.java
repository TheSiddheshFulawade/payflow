package com.siddheshfulawade.payflow.wallet.api;

import com.siddheshfulawade.payflow.wallet.app.WalletAdminService;
import com.siddheshfulawade.payflow.wallet.app.WalletNotFoundException;
import com.siddheshfulawade.payflow.wallet.app.WalletView;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reading a wallet. */
@RestController
@RequestMapping("/api/v1/wallets")
class WalletController {

    private final WalletAdminService wallets;

    WalletController(WalletAdminService wallets) {
        this.wallets = wallets;
    }

    @GetMapping("/{id}")
    WalletView read(@PathVariable @Pattern(regexp = CreateWalletRequest.WALLET_ID) String id) {
        return wallets.find(id).orElseThrow(() -> new WalletNotFoundException(id));
    }
}
