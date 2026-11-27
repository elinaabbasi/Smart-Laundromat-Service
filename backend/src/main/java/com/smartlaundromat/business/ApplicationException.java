package com.smartlaundromat.business;

public class ApplicationException extends RuntimeException {
    private final Reason reason;

    public ApplicationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_INPUT, NOT_FOUND, CONFLICT, PAYMENT_NOT_CONFIGURED, PAYMENT_PROVIDER_ERROR
    }
}
