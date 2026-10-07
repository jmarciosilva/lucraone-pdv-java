package br.com.lucraone.pdv.domain.terminal;

import java.time.Instant;
import java.util.Objects;

/**
 * Backend references that make this installation a provisioned terminal. Both references are required
 * together and remain opaque text until the API contract defines their format.
 */
public record TerminalProvisioning(String terminalId, String branchId, Instant provisionedAt) {

    public static final int MAX_REFERENCE_LENGTH = 64;

    public TerminalProvisioning {
        terminalId = requireReference(terminalId, "terminalId");
        branchId = requireReference(branchId, "branchId");
        Objects.requireNonNull(provisionedAt, "provisionedAt must not be null");
    }

    private static String requireReference(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String reference = value.strip();
        if (reference.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (reference.length() > MAX_REFERENCE_LENGTH) {
            throw new IllegalArgumentException(name + " must have at most " + MAX_REFERENCE_LENGTH + " characters");
        }
        if (reference.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        return reference;
    }
}
