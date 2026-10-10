package br.com.lucraone.pdv.infrastructure.security;

import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.auth.*;
import br.com.lucraone.pdv.application.security.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class MachineCredentialRepositoryTest {
    private static final Instant EXPIRY = Instant.parse("2026-12-31T23:59:59Z");
    private MemoryStore store;
    private SecretStoreMachineCredentialRepository repository;
    private MachineCredential credential;
    @BeforeEach void setup() {
        store = new MemoryStore(); repository = new SecretStoreMachineCredentialRepository(store);
        credential = new MachineCredential("Bearer", "lucraone-test-secret-" + UUID.randomUUID(), EXPIRY);
    }
    @Test void roundTrip() { var saved=repository.save(credential); assertEquals(saved,repository.load().orElseThrow()); }
    @Test void overwriteChangesGenerationAndToken() {
        var first=repository.save(credential); var second=repository.save(new MachineCredential("Bearer","synthetic-new",EXPIRY));
        assertNotEquals(first.generation(),second.generation()); assertEquals(second,repository.load().orElseThrow());
    }
    @Test void deleteAndMissing() { assertTrue(repository.load().isEmpty()); repository.save(credential); repository.delete(); repository.delete(); assertTrue(repository.load().isEmpty()); }
    @Test void expiryPreserved() { repository.save(credential); assertEquals(EXPIRY,repository.load().orElseThrow().credential().expiresAt()); }
    @Test void restorePreservesGeneration() { var old=repository.save(credential); repository.save(new MachineCredential("Bearer","synthetic-new",EXPIRY)); repository.save(old); assertEquals(old,repository.load().orElseThrow()); }
    @Test void payloadVersionOne() { repository.save(credential); assertTrue(new String(store.bytes,StandardCharsets.UTF_8).contains("\"version\":1")); }
    @Test void unsupportedVersion() { bad(payload().replace("\"version\":1","\"version\":2")); }
    @Test void corruptPayload() { bad("not json " + credential.accessToken()); }
    @Test void emptyToken() { bad(payload().replace(credential.accessToken(),"")); }
    @Test void invalidType() { bad(payload().replace("Bearer","Basic")); }
    @Test void invalidExpiry() { bad(payload().replace(EXPIRY.toString(),"invalid")); }
    @Test void missingField() { bad("{\"version\":1}"); }
    @Test void invalidGeneration() { bad(payload().replace(store.generation,"not-uuid")); }
    @Test void nullPayloadField() { bad(payload().replace("\"Bearer\"","null")); }
    @Test void duplicateField() { bad(payload().replace("\"version\":1","\"version\":1,\"version\":1")); }
    @Test void trailingPayload() { bad(payload()+" {}"); }
    @Test void invalidTokenTypeOnSave() { assertThrows(IllegalArgumentException.class, () -> repository.save(new MachineCredential("Basic","synthetic",EXPIRY))); }
    @Test void secretStoreUnavailable() { store.failure=true; assertEquals(CredentialStorageException.Kind.UNAVAILABLE,assertThrows(CredentialStorageException.class,repository::load).kind()); }
    @Test void buffersAreWipedAfterSaveAndLoad() { repository.save(credential); assertAllZero(store.savedInput); repository.load(); assertAllZero(store.loadedOutput); }
    @Test void descriptionsNeverExposeSecret() { var saved=repository.save(credential); assertFalse(saved.toString().contains(credential.accessToken())); assertFalse(repository.toString().contains(credential.accessToken())); }
    @Test void readinessUsesSeparateKeyAndCleansUp() { repository.save(credential); repository.verifyWritable(); assertEquals(Set.of("machine-credential-v1"),store.values.keySet()); assertEquals(credential,repository.load().orElseThrow().credential()); }
    @Test void readinessFailsBeforeReturningOnUnavailable() { store.failure=true; assertThrows(CredentialStorageException.class,repository::verifyWritable); }
    @Test void saveFailureIsSanitized() {
        store.leaky=true; var error=assertThrows(CredentialStorageException.class, () -> repository.save(credential));
        assertNull(error.getCause()); assertFalse(error.toString().contains(credential.accessToken()));
    }
    @Test void oversizedSaveCannotReplaceExistingProtectedRecord() {
        var old = repository.save(credential);
        var oversized = new MachineCredential("Bearer", "synthetic-" + "x".repeat(1024 * 1024), EXPIRY);
        assertThrows(CredentialStorageException.class, () -> repository.save(oversized));
        assertEquals(old, repository.load().orElseThrow());
        assertAllZero(store.savedInput);
    }
    private String payload() { repository.save(credential); store.generation=repository.load().orElseThrow().generation().toString(); return new String(store.bytes,StandardCharsets.UTF_8); }
    private void bad(String value) {
        store.values.put("machine-credential-v1",value.getBytes(StandardCharsets.UTF_8));
        var error=assertThrows(CredentialStorageException.class,repository::load);
        assertEquals(CredentialStorageException.Kind.INVALID_PAYLOAD,error.kind()); assertNull(error.getCause());
        assertFalse(error.toString().contains(credential.accessToken()));
    }
    private void assertAllZero(byte[] bytes) { for(byte b:bytes) assertEquals(0,b); }
    static class MemoryStore implements SecretStore {
        Map<String,byte[]> values=new HashMap<>(); byte[] bytes,savedInput,loadedOutput; String generation; boolean failure,leaky;
        public void save(String key,byte[] value) { if(failure) throw new SecretStoreException(SecretStoreException.Kind.UNAVAILABLE); if(leaky) throw new RuntimeException(new String(value,StandardCharsets.UTF_8)); savedInput=value; bytes=value.clone(); values.put(key,bytes); }
        public Optional<byte[]> load(String key) { if(failure) throw new SecretStoreException(SecretStoreException.Kind.UNAVAILABLE); return Optional.ofNullable(values.get(key)).map(v -> { loadedOutput=v.clone(); return loadedOutput; }); }
        public void delete(String key) { if(failure) throw new SecretStoreException(SecretStoreException.Kind.UNAVAILABLE); values.remove(key); }
    }
}
