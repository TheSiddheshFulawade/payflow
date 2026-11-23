package com.siddheshfulawade.payflow.wallet.api;

import com.siddheshfulawade.payflow.wallet.app.WalletAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Seeding and freezing wallets. PF-07 restricts it to the {@code ops} role. */
@RestController
@RequestMapping("/api/v1/ops/wallets")
class OpsWalletController {

    private final WalletAdminService wallets;

    OpsWalletController(WalletAdminService wallets) {
        this.wallets = wallets;
    }

    /** No Idempotency-Key: the wallet id is the key. A retried seed gets 409 and changes nothing. */
    @PostMapping
    ResponseEntity<Void> seed(@Valid @RequestBody CreateWalletRequest request) {
        wallets.seed(request.id(), request.ownerSub(), request.initialBalance());
        return ResponseEntity.created(URI.create("/api/v1/wallets/" + request.id()))
                .build();
    }

    @PutMapping("/{id}/status")
    void changeStatus(
            @PathVariable @Pattern(regexp = CreateWalletRequest.WALLET_ID) String id,
            @Valid @RequestBody ChangeStatusRequest request) {
        wallets.changeStatus(id, request.status());
    }
}
