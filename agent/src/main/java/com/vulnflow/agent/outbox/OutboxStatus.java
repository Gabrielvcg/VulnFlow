package com.vulnflow.agent.outbox;

public enum OutboxStatus {
    PENDING,
    UPLOADING,
    RETRY_WAIT,
    UPLOADED,
    DEAD_LETTER
}
