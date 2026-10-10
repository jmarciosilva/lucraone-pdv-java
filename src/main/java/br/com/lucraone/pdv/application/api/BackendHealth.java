package br.com.lucraone.pdv.application.api;

import java.util.Objects;

/**
 * Unauthenticated liveness answer from the backend.
 *
 * @param status    backend-reported status, {@code ok} when healthy
 * @param api       which API answered, {@code pdv} for this client
 * @param version   contract version reported by the backend
 * @param requestId correlation id for support
 */
public record BackendHealth(String status, String api, String version, String requestId) {

    public BackendHealth {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(api, "api must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
    }

    /**
     * Whether the backend speaks the contract version this build was written against. A mismatch is
     * reported rather than enforced: this phase records the difference for a later decision instead of
     * negotiating versions.
     */
    public boolean versionSupported() {
        return PdvContract.SUPPORTED_VERSION.equals(version);
    }
}
