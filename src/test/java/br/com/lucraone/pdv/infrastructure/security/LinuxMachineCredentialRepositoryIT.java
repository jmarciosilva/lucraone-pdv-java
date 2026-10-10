package br.com.lucraone.pdv.infrastructure.security;

import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.auth.StoredMachineCredential;
import br.com.lucraone.pdv.application.security.SecretStore;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit invocation only. A unique synthetic namespace never touches the operational credential. */
class LinuxMachineCredentialRepositoryIT {
    @Test void protectedEnvelopeRestoreRotationReadinessAndCleanup() {
        var nativeStore = new LinuxSecretStore();
        String key = "lucraone-test-credential-" + UUID.randomUUID();
        SecretStore isolated = new SecretStore() {
            String mapped(String source) { return source.equals("machine-credential-v1") ? key : source; }
            public void save(String k,byte[] secret){nativeStore.save(mapped(k),secret);}
            public Optional<byte[]> load(String k){return nativeStore.load(mapped(k));}
            public void delete(String k){nativeStore.delete(mapped(k));}
        };
        var repository = new SecretStoreMachineCredentialRepository(isolated);
        try {
            assertTrue(repository.load().isEmpty());
            repository.verifyWritable();
            var original = repository.save(new MachineCredential("Bearer", "lucraone-test-secret-" + UUID.randomUUID(), Instant.parse("2030-01-01T00:00:00Z")));
            assertEquals(Optional.of(original), new SecretStoreMachineCredentialRepository(isolated).load());
            var replacement = repository.save(new MachineCredential("Bearer", "lucraone-test-secret-" + UUID.randomUUID(), Instant.parse("2030-02-01T00:00:00Z")));
            assertNotEquals(original.generation(), replacement.generation());
            assertEquals(Optional.of(replacement), repository.load());
            repository.delete(); assertTrue(repository.load().isEmpty());
        } finally {
            nativeStore.delete(key);
            Optional<byte[]> remaining = nativeStore.load(key);
            remaining.ifPresent(bytes -> Arrays.fill(bytes, (byte) 0));
            assertTrue(remaining.isEmpty(), "Synthetic credential cleanup must be confirmed");
        }
    }
}
