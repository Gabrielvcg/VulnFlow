package com.vulnflow.agent.outbox;

public record OutboxStats(long pending, long retrying, long uploading, long uploaded, long deadLetters) {
}
