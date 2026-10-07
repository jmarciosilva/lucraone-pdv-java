package br.com.lucraone.pdv.application.security;

/**
 * Indicates that a secret could not be protected or recovered. Messages never contain secret material.
 */
public final class SecretProtectionException extends RuntimeException {

    public SecretProtectionException(String message) {
        super(message);
    }

    public SecretProtectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
