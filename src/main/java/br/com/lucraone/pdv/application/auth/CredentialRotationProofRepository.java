package br.com.lucraone.pdv.application.auth;

import java.util.Optional;

/** Stores the client-held delivery secret only in the operating system's protected secret store. */
public interface CredentialRotationProofRepository {
    record Proof(String operationId, String deliverySecret) {
        @Override public String toString() { return "CredentialRotationProof[redacted]"; }
    }
    void save(Proof proof);
    Optional<Proof> load();
    void delete();
}
