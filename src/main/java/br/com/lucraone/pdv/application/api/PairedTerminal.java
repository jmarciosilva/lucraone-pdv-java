package br.com.lucraone.pdv.application.api;

import java.util.Objects;

/**
 * Successful pairing outcome: the commercial context plus the machine credential to use from now on.
 *
 * <p>Carries a secret through {@link MachineCredential}, so {@link #toString()} is redacted.
 */
public record PairedTerminal(TerminalContext context, MachineCredential credential, String requestId) {

    public PairedTerminal {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(credential, "credential must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
    }

    @Override
    public String toString() {
        return "PairedTerminal[terminalId=" + context.terminal().id()
                + ", credential=***, requestId=" + requestId + "]";
    }
}
