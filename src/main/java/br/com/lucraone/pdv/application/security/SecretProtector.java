package br.com.lucraone.pdv.application.security;

/**
 * Protects secrets at rest with a mechanism managed by the operating system. Implementations never log or
 * expose plaintext, and callers should clear plaintext arrays once they are no longer needed.
 */
public interface SecretProtector {

    byte[] protect(byte[] plaintext);

    byte[] unprotect(byte[] protectedData);
}
