package com.siddheshfulawade.payflow.wallet.app;

public class WalletNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WalletNotFoundException(String id) {
        super("wallet " + id + " not found");
    }
}
