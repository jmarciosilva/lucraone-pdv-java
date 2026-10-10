package br.com.lucraone.pdv.application.auth;

import br.com.lucraone.pdv.application.api.PdvFailureKind;
import java.time.Instant;
import java.util.Optional;

/** Sanitized UI result: never carries a credential or a backend message/body. */
public record TerminalAuthSnapshot(TerminalAuthState state, Optional<String> requestId,
        Optional<PdvFailureKind> failureKind, Optional<Instant> expiresAt) {
    public static TerminalAuthSnapshot of(TerminalAuthState state) {
        return new TerminalAuthSnapshot(state, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
