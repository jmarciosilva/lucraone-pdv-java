package br.com.lucraone.pdv.application.api;

import java.time.Instant;

/** Session token; access token is never logged. */
public record OperatorCredential(String tokenType, String accessToken, Instant expiresAt) {

    @Override
    public String toString() {
        return "OperatorCredential[tokenType=" + tokenType + ",accessToken=REDACTED,expiresAt=" + expiresAt + "]";
    }
}
