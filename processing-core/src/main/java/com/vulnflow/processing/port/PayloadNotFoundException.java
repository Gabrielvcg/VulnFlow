package com.vulnflow.processing.port;

public class PayloadNotFoundException extends RuntimeException {
    public PayloadNotFoundException(String message) {
        super(message);
    }

    public PayloadNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
