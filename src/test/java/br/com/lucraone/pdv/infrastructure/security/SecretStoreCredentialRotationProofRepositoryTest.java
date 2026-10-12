package br.com.lucraone.pdv.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;

import br.com.lucraone.pdv.application.auth.CredentialRotationProofRepository;
import br.com.lucraone.pdv.application.auth.CredentialStorageException;
import br.com.lucraone.pdv.application.security.SecretStore;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SecretStoreCredentialRotationProofRepositoryTest {
    private static final String KEY = "machine-credential-rotation-proof-v1";
    private static final CredentialRotationProofRepository.Proof PROOF =
            new CredentialRotationProofRepository.Proof("11111111-2222-4333-8444-555555555555", "c".repeat(64));

    @Test void writesAndReloadsProofOnlyFromProtectedStoreAndWipesBuffers() {
        MemoryStore store = new MemoryStore(); var repo = new SecretStoreCredentialRotationProofRepository(store);
        repo.save(PROOF);
        assertEquals(PROOF, repo.load().orElseThrow());
        assertTrue(store.values.containsKey(KEY));
        assertTrue(Arrays.stream(toUnsigned(store.loadedOutput)).allMatch(value -> value == 0));
        assertFalse(repo.toString().contains(PROOF.deliverySecret()));
        assertFalse(PROOF.toString().contains(PROOF.deliverySecret()));
        repo.delete(); assertTrue(repo.load().isEmpty());
    }

    @Test void rejectsCorruptOrMalformedProofWithoutLeakingSecret() {
        MemoryStore store = new MemoryStore(); var repo = new SecretStoreCredentialRotationProofRepository(store);
        assertThrows(CredentialStorageException.class, () -> repo.save(new CredentialRotationProofRepository.Proof("bad", "x")));
        store.values.put(KEY, ("bad " + PROOF.deliverySecret()).getBytes(StandardCharsets.UTF_8));
        var failure = assertThrows(CredentialStorageException.class, repo::load);
        assertEquals(CredentialStorageException.Kind.INVALID_PAYLOAD, failure.kind());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(PROOF.deliverySecret()));
    }

    private static final class MemoryStore implements SecretStore {
        final Map<String, byte[]> values = new HashMap<>(); byte[] loadedOutput;
        public void save(String key, byte[] secret) { values.put(key, secret.clone()); }
        public Optional<byte[]> load(String key) { return Optional.ofNullable(values.get(key)).map(value -> {
            loadedOutput = value.clone(); return loadedOutput;
        }); }
        public void delete(String key) { values.remove(key); }
    }
    private static int[] toUnsigned(byte[] bytes) { return java.util.stream.IntStream.range(0, bytes.length).map(i -> bytes[i] & 0xff).toArray(); }
}
