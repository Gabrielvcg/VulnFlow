package com.vulnflow.ingestion;

public class TransientReportStorageException extends ReportStorageException {

    public TransientReportStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
