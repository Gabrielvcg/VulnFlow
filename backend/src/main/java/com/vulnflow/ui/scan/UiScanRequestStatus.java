package com.vulnflow.ui.scan;

public enum UiScanRequestStatus {
    REQUESTED, CLAIMED, RUNNING, UPLOADING, PROCESSING, COMPLETED, FAILED;
    public boolean terminal() { return this == COMPLETED || this == FAILED; }
}
