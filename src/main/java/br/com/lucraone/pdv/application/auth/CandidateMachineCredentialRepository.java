package br.com.lucraone.pdv.application.auth;

import br.com.lucraone.pdv.application.api.MachineCredential;
import java.util.Optional;

/**
 * Port for the rotation candidate credential. Uses a separate SecretStore key so the current
 * machine credential and the candidate never interfere.
 */
public interface CandidateMachineCredentialRepository {

    /** Persists the candidate credential (token_type is also Bearer during rotation). */
    void save(MachineCredential candidate);

    Optional<StoredMachineCredential> load();

    void delete();
}
