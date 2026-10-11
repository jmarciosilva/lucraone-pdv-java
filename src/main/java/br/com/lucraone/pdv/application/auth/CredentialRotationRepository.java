package br.com.lucraone.pdv.application.auth;

import java.time.Instant;
import java.util.Optional;

/**
 * Port for persisting and retrieving the in-progress credential rotation record.
 * The operation_id is written BEFORE the HTTP rotation call so that a crash leaves
 * a recoverable record.
 */
public interface CredentialRotationRepository {

    record PendingRotation(String operationId, Instant startedAt) {}

    /** Persists or replaces the pending rotation (singleton row). */
    void save(PendingRotation rotation);

    /** Returns the pending rotation, if any. */
    Optional<PendingRotation> load();

    /** Removes the pending rotation record. Idempotent. */
    void delete();
}
