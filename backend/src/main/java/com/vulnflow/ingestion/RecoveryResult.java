package com.vulnflow.ingestion;

public record RecoveryResult(int retried, int deadLettered) {

    public static RecoveryResult none() {
        return new RecoveryResult(0, 0);
    }
}
