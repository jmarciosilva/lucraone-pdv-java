package br.com.lucraone.pdv.application.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Request to pair this installation with a terminal the backend already knows.
 *
 * <p>The pairing code is single-use and sensitive, so {@link #toString()} is redacted. The code format
 * is defined and validated by the backend; this client only rejects a blank value rather than
 * duplicating a server-side rule that could reject otherwise valid codes.
 *
 * @param pairingCode    operator-provided pairing code
 * @param installationId local installation identity created in phase 3
 */
public record PairTerminalCommand(String pairingCode, UUID installationId) {

    public PairTerminalCommand {
        Objects.requireNonNull(pairingCode, "pairingCode must not be null");
        Objects.requireNonNull(installationId, "installationId must not be null");
        pairingCode = pairingCode.strip();
        if (pairingCode.isEmpty()) {
            throw new IllegalArgumentException("pairingCode must not be blank");
        }
    }

    @Override
    public String toString() {
        return "PairTerminalCommand[pairingCode=***, installationId=" + installationId + "]";
    }
}
