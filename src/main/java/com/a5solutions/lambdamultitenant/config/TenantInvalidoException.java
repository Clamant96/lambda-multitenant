package com.a5solutions.lambdamultitenant.config;

public class TenantInvalidoException extends RuntimeException {

    public TenantInvalidoException(String message, Throwable cause) {
        super(message, cause);
    }
}
