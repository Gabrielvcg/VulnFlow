package com.vulnflow.shared.exception;

public class InvalidReportException extends RuntimeException {

    public InvalidReportException(String message) {
        super(message);
    }

    public InvalidReportException(String message, Throwable cause) {
        super(message, cause);
    }
}

