package com.vulnflow.processing;

import java.util.Locale;

public enum FindingSeverity {
    UNKNOWN,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public static FindingSeverity fromExternalValue(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
