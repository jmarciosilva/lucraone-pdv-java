package br.com.lucraone.pdv.application.security;

public final class SecretStoreException extends RuntimeException {
    public enum Kind { UNAVAILABLE, READ_FAILED, WRITE_FAILED, DELETE_FAILED }
    private final Kind kind;
    public SecretStoreException(Kind kind) {
        super("Armazenamento seguro: " + java.util.Objects.requireNonNull(kind).name());
        this.kind = kind;
    }
    public Kind kind() { return kind; }
}
