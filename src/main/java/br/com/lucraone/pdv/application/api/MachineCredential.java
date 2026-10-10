package br.com.lucraone.pdv.application.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Machine credential issued by the backend when a terminal is paired.
 *
 * <p>This value is secret. In this phase it exists only in memory, for the lifetime of the calling
 * flow: it is never written to the local database, to a properties file or to any other store, and
 * persistence is deliberately deferred until a platform secret store exists.
 *
 * <p>{@link #toString()} is redacted on purpose, so the credential cannot reach a log through a
 * record's generated representation.
 *
 * @param tokenType   scheme reported by the backend, {@code Bearer}
 * @param accessToken the secret itself
 * @param expiresAt   instant after which the backend will reject the credential
 */
public record MachineCredential(String tokenType, String accessToken, Instant expiresAt) {

    public MachineCredential {
        Objects.requireNonNull(tokenType, "tokenType must not be null");
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (tokenType.isBlank()) {
            throw new IllegalArgumentException("tokenType must not be blank");
        }
        if (accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken must not be blank");
        }
    }

    public boolean expiredAt(Instant reference) {
        return !reference.isBefore(expiresAt);
    }

    @Override
    public String toString() {
        return "MachineCredential[tokenType=" + tokenType + ", accessToken=***, expiresAt=" + expiresAt + "]";
    }
}
