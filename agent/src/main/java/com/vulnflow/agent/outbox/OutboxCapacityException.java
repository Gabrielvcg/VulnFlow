package com.vulnflow.agent.outbox;

public class OutboxCapacityException extends RuntimeException {

    public OutboxCapacityException(String message) {
        super(message);
    }
}
