package br.com.lucraone.pdv.application.auth;

import br.com.lucraone.pdv.application.api.MachineCredential;
import java.util.Objects;
import java.util.UUID;

/** Generation binds local validation evidence to exactly one protected credential. */
public record StoredMachineCredential(UUID generation, MachineCredential credential) {
    public StoredMachineCredential { Objects.requireNonNull(generation); Objects.requireNonNull(credential); }
    @Override public String toString() { return "StoredMachineCredential[credential=***]"; }
}
