package com.vulnflow.ui.auth;

public class UiAuthenticationException extends RuntimeException {
    private final String code;
    public UiAuthenticationException(String code, String message) { super(message); this.code = code; }
    public String getCode() { return code; }
}
