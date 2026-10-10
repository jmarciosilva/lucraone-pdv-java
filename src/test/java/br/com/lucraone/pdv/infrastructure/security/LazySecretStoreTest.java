package br.com.lucraone.pdv.infrastructure.security;

import br.com.lucraone.pdv.application.security.SecretStoreException;
import br.com.lucraone.pdv.application.auth.CredentialStorageException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LazySecretStoreTest {
    @Test void concurrentFirstReadsInitializeExactlyOneProvider() throws Exception {
        var calls = new AtomicInteger();
        var store = new LazySecretStore(() -> {
            calls.incrementAndGet();
            return new MachineCredentialRepositoryTest.MemoryStore();
        });
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var reads = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                reads.add(workers.submit(() -> { start.await(); return store.load("synthetic-key").isEmpty(); }));
            }
            start.countDown();
            for (var read : reads) assertTrue(read.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        }
    }
    @Test void nativeInitializationFailureIsUnavailableNotMissing() {
        var calls = new AtomicInteger();
        var store = new LazySecretStore(() -> { calls.incrementAndGet(); throw new UnsatisfiedLinkError("synthetic-secret"); });
        assertEquals(0, calls.get());
        var error = assertThrows(SecretStoreException.class, () -> store.load("test"));
        assertEquals(SecretStoreException.Kind.UNAVAILABLE, error.kind());
        assertNull(error.getCause()); assertFalse(error.toString().contains("synthetic-secret"));
    }
    @Test void macOsCredentialLifecycleRemainsFailSecure() {
        var repository = new SecretStoreMachineCredentialRepository(new LazySecretStore(MacOsSecretStore::new));
        assertEquals(CredentialStorageException.Kind.UNAVAILABLE, assertThrows(CredentialStorageException.class, repository::load).kind());
        assertThrows(CredentialStorageException.class, repository::verifyWritable);
    }
}
