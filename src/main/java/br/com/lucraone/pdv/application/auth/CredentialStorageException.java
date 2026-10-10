package br.com.lucraone.pdv.application.auth;

public final class CredentialStorageException extends RuntimeException {
    public enum Kind { UNAVAILABLE, INVALID_PAYLOAD, READ_FAILED, WRITE_FAILED, DELETE_FAILED }
    private final Kind kind;
    public CredentialStorageException(Kind kind) { super("Credential storage: " + kind.name()); this.kind = kind; }
    public Kind kind() { return kind; }
}
