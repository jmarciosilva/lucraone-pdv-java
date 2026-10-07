package br.com.lucraone.pdv.domain.terminal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Local identity of this application installation. It is generated once on the terminal and is not the
 * commercial terminal identity assigned by the backend.
 */
public record Installation(UUID installationId, Instant createdAt) {

    public Installation {
        Objects.requireNonNull(installationId, "installationId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
