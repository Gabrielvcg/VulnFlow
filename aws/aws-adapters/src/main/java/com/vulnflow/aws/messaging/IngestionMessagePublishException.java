package com.vulnflow.aws.messaging;

public class IngestionMessagePublishException extends RuntimeException {
    public IngestionMessagePublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
