package br.com.lucraone.pdv.application.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Authenticated view of the terminal this credential belongs to. The backend never returns an access
 * token here, only the expiry of the credential already in use.
 */
public record CurrentTerminal(TerminalContext context, Instant credentialExpiresAt, String requestId) {

    public CurrentTerminal {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(credentialExpiresAt, "credentialExpiresAt must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
    }
}
