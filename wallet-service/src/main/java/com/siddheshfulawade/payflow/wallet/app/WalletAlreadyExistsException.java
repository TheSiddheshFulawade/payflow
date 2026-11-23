package com.siddheshfulawade.payflow.wallet.app;

public class WalletAlreadyExistsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WalletAlreadyExistsException(String id) {
        super("wallet " + id + " already exists");
    }
}
