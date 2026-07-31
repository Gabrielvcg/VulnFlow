package com.vulnflow.ingestion;

public class PayloadIntegrityException extends RuntimeException {

    public PayloadIntegrityException(String message) {
        super(message);
    }
}
