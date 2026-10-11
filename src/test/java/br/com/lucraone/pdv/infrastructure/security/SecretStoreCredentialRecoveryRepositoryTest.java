package br.com.lucraone.pdv.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.application.auth.CredentialRecoveryRepository;
import br.com.lucraone.pdv.application.auth.CredentialStorageException;
import br.com.lucraone.pdv.application.security.SecretStore;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecretStoreCredentialRecoveryRepositoryTest {
    private static final String KEY = "machine-credential-recovery-proof-v1";
    private static final String CODE = "ABC123.abcdef123456";
    private static final String SECRET = "b".repeat(64);

    @Test void persistsPendingProofOnlyUnderProtectedSecretStoreKeyAndWipesBuffers() {
        MemoryStore store = new MemoryStore();
        var repository = new SecretStoreCredentialRecoveryRepository(store);
        var proof = new CredentialRecoveryRepository.Proof(CODE, UUID.fromString("11111111-2222-4333-8444-555555555555"), SECRET);
        repository.save(proof);
        assertTrue(store.values.containsKey(KEY));
        assertEquals(proof, repository.load().orElseThrow());
        assertTrue(Arrays.stream(toUnsigned(store.loadedOutput)).allMatch(value -> value == 0));
        assertFalse(repository.toString().contains(SECRET));
        repository.delete();
        assertTrue(repository.load().isEmpty());
    }

    @Test void supportsPersistedOperationIdAndRejectsCorruptProofWithoutLeakingIt() {
        MemoryStore store = new MemoryStore();
        var repository = new SecretStoreCredentialRecoveryRepository(store);
        UUID installation = UUID.fromString("11111111-2222-4333-8444-555555555555");
        repository.save(new CredentialRecoveryRepository.Proof(CODE, installation, SECRET, "op-test"));
        assertEquals("op-test", repository.load().orElseThrow().operationId());
        store.values.put(KEY, ("bad " + SECRET).getBytes(StandardCharsets.UTF_8));
        var failure = assertThrows(CredentialStorageException.class, repository::load);
        assertEquals(CredentialStorageException.Kind.INVALID_PAYLOAD, failure.kind());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(SECRET));
    }

    @Test void refusesMalformedRecoveryMaterialBeforeWriting() {
        MemoryStore store = new MemoryStore();
        var repository = new SecretStoreCredentialRecoveryRepository(store);
        assertThrows(CredentialStorageException.class, () -> repository.save(new CredentialRecoveryRepository.Proof("short", UUID.randomUUID(), SECRET)));
        assertTrue(store.values.isEmpty());
    }

    private static int[] toUnsigned(byte[] bytes) { return java.util.stream.IntStream.range(0, bytes.length).map(i -> bytes[i] & 0xff).toArray(); }
    private static final class MemoryStore implements SecretStore {
        final Map<String, byte[]> values = new HashMap<>(); byte[] loadedOutput;
        public void save(String key, byte[] secret) { values.put(key, secret.clone()); }
        public Optional<byte[]> load(String key) { return Optional.ofNullable(values.get(key)).map(value -> { loadedOutput = value.clone(); return loadedOutput; }); }
        public void delete(String key) { values.remove(key); }
    }
}
