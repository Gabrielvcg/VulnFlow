package com.vulnflow.agent.client;

public class VulnFlowClientException extends RuntimeException {

    private final ClientFailureKind kind;
    private final String safeError;

    public VulnFlowClientException(ClientFailureKind kind, String safeError) {
        super(safeError);
        this.kind = kind;
        this.safeError = safeError;
    }

    public VulnFlowClientException(ClientFailureKind kind, String safeError, Throwable cause) {
        super(safeError, cause);
        this.kind = kind;
        this.safeError = safeError;
    }

    public ClientFailureKind kind() {
        return kind;
    }

    public String safeError() {
        return safeError;
    }
}
