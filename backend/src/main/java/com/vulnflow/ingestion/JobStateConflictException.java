package com.vulnflow.ingestion;

public class JobStateConflictException extends RuntimeException {

    public JobStateConflictException(String message) {
        super(message);
    }
}
