package com.vulnflow.agent.outbox;

public class OutboxIntegrityException extends RuntimeException {

    public OutboxIntegrityException(String message) {
        super(message);
    }

    public OutboxIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
