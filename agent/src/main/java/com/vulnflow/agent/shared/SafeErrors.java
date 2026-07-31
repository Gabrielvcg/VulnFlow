package com.vulnflow.agent.shared;

public final class SafeErrors {

    private static final int MAX_LENGTH = 500;

    private SafeErrors() {
    }

    public static String limited(String value) {
        if (value == null || value.isBlank()) {
            return "Unspecified agent failure";
        }
        String singleLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() <= MAX_LENGTH ? singleLine : singleLine.substring(0, MAX_LENGTH);
    }
}
