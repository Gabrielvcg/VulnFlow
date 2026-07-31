package com.vulnflow.ingestion;

public class StaleJobClaimException extends RuntimeException {

    public StaleJobClaimException(String message) {
        super(message);
    }
}
