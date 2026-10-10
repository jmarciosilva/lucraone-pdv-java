package br.com.lucraone.pdv.application.auth;

import br.com.lucraone.pdv.application.api.MachineCredential;
import java.util.Optional;
import java.util.UUID;

public interface MachineCredentialRepository {
    void save(StoredMachineCredential value);
    default StoredMachineCredential save(MachineCredential credential) {
        StoredMachineCredential value = new StoredMachineCredential(UUID.randomUUID(), credential);
        save(value); return value;
    }
    Optional<StoredMachineCredential> load();
    void delete();
    /** Actual isolated write/read/delete, not a promise of future provider availability. */
    void verifyWritable();
}
