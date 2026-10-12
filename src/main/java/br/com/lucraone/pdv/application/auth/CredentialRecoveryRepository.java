package br.com.lucraone.pdv.application.auth;

import java.util.Optional;
import java.util.UUID;

/** Stores only the pending recovery proof inside the OS protected SecretStore. */
public interface CredentialRecoveryRepository {
    record Proof(String recoveryCode, UUID installationId, String deliverySecret, String operationId) {
        public Proof(String recoveryCode, UUID installationId, String deliverySecret) {
            this(recoveryCode, installationId, deliverySecret, null);
        }
        public Proof withOperationId(String id) { return new Proof(recoveryCode, installationId, deliverySecret, id); }
        @Override public String toString() { return "CredentialRecoveryProof[redacted]"; }
    }
    void save(Proof proof);
    Optional<Proof> load();
    void delete();
}
