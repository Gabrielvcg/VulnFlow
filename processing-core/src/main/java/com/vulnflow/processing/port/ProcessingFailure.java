package com.vulnflow.processing.port;

import java.time.Instant;
import java.util.Objects;

public record ProcessingFailure(String code, String safeMessage, Instant failedAt) {
    public ProcessingFailure {
        code = requireText(code, "code", 64);
        safeMessage = requireText(safeMessage, "safeMessage", 500);
        failedAt = Objects.requireNonNull(failedAt, "failedAt");
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is outside the supported length");
        }
        return normalized;
    }
}
