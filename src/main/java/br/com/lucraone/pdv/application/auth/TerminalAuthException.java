package br.com.lucraone.pdv.application.auth;

import java.util.Optional;

/** Fixed failure categories; no nested causes that might contain tokens or pairing codes. */
public final class TerminalAuthException extends RuntimeException {
    public enum Kind { CONTEXT_MISMATCH, CREDENTIAL_EXPIRED, PERSISTENCE_FAILED, COMPENSATION_FAILED, SECURE_STORE_UNAVAILABLE, BACKEND_FAILURE, BACKEND_NOT_CONFIGURED }
    private final Kind kind;
    private final Optional<String> requestId;
    public TerminalAuthException(Kind kind, String requestId) {
        super("Terminal authentication: " + kind.name()); this.kind = kind; this.requestId = Optional.ofNullable(requestId);
    }
    public Kind kind() { return kind; }
    public Optional<String> requestId() { return requestId; }
}
