package com.vulnflow.processing;

public class PayloadIntegrityException extends RuntimeException {
    public PayloadIntegrityException(String message) {
        super(message);
    }
}
