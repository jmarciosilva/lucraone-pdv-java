package br.com.lucraone.pdv.application.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Non-secret proof of a successful currentTerminal response, scoped to credential/context/backend. */
public record TerminalValidation(UUID generation, UUID installationId, String terminalId, String branchId,
        String backendIdentity, Instant validatedAt) {
    public TerminalValidation {
        Objects.requireNonNull(generation); Objects.requireNonNull(installationId);
        Objects.requireNonNull(terminalId); Objects.requireNonNull(branchId);
        Objects.requireNonNull(backendIdentity); Objects.requireNonNull(validatedAt);
    }
}
