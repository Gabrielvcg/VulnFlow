package com.vulnflow.ingestion;

public class PayloadNotFoundException extends RuntimeException {

    public PayloadNotFoundException(String message) {
        super(message);
    }
}
