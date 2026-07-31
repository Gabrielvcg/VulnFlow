package com.vulnflow.contract;

public class UnsupportedEventVersionException extends IllegalArgumentException {
    public UnsupportedEventVersionException(String version) {
        super("Unsupported ingestion event version: " + version);
    }
}
