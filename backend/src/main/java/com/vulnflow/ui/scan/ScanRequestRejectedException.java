package com.vulnflow.ui.scan;
public class ScanRequestRejectedException extends RuntimeException { private final String code; public ScanRequestRejectedException(String code,String message){super(message);this.code=code;} public String getCode(){return code;} }
