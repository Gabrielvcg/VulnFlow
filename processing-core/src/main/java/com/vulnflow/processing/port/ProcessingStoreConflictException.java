package com.vulnflow.processing.port;

public class ProcessingStoreConflictException extends RuntimeException {
    public ProcessingStoreConflictException(String message) {
        super(message);
    }
}
