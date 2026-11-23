package com.siddheshfulawade.payflow.wallet.api;

import com.siddheshfulawade.payflow.wallet.domain.WalletStatus;
import jakarta.validation.constraints.NotNull;

record ChangeStatusRequest(@NotNull WalletStatus status) {}
