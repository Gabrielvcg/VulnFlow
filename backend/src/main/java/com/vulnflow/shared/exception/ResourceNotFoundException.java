package com.vulnflow.shared.exception;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resourceType, Object id) {
        super(resourceType + " with id '" + id + "' was not found");
    }
}

