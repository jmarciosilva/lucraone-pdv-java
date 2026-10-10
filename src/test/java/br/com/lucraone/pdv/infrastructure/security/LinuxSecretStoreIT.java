package br.com.lucraone.pdv.infrastructure.security;

import br.com.lucraone.pdv.application.security.SecretStoreException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit invocation only; never uses real credentials or backend Internet access. */
class LinuxSecretStoreIT {
    @Test void realNativeStoreRoundTripAndCleanup() {
        var store = new LinuxSecretStore();
        String key = "lucraone-test-" + UUID.randomUUID();
        byte[] secret = ("lucraone-test-secret-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        byte[] replacement = {0, 1, 2, (byte) 255, 0, 10};
        byte[] loaded = null;
        try {
            assertTrue(store.load(key).isEmpty());
            store.save(key, secret);
            loaded = store.load(key).orElseThrow();
            assertArrayEquals(secret, loaded);
            Arrays.fill(loaded, (byte) 0); loaded = null;
            store.save(key, replacement);
            loaded = store.load(key).orElseThrow();
            assertArrayEquals(replacement, loaded);
            store.delete(key);
            assertTrue(store.load(key).isEmpty());
        } finally {
            if (loaded != null) Arrays.fill(loaded, (byte) 0);
            Arrays.fill(secret, (byte) 0); Arrays.fill(replacement, (byte) 0);
            store.delete(key);
            assertTrue(store.load(key).isEmpty(), "Synthetic secret cleanup must be confirmed");
        }
    }
}
