package br.com.lucraone.pdv.infrastructure.security;

import br.com.lucraone.pdv.application.security.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SecretStoreContractTest {
    @TempDir Path directory;
    private List<SecretStore> stores;
    private static final byte[] SECRET = "lucraone-test-secret-synthetic".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    @BeforeEach void setup() {
        SecretProtector fake = new SecretProtector() {
            public byte[] protect(byte[] input) { byte[] result = input.clone(); for (int i=0;i<result.length;i++) result[i]^=0x55; return result; }
            public byte[] unprotect(byte[] input) { return protect(input); }
        };
        NativeSecretProvider nativeFake = new NativeSecretProvider() {
            private final Map<String, byte[]> values = new HashMap<>();
            public void save(String key, byte[] secret) { values.put(key, secret.clone()); }
            public Optional<byte[]> load(String key) { return Optional.ofNullable(values.get(key)).map(byte[]::clone); }
            public void delete(String key) { values.remove(key); }
        };
        stores = List.of(new WindowsSecretStore(directory, fake), new LinuxSecretStore(nativeFake));
    }
    @Test void saveAndLoad() { for (var store: stores) { store.save("test", SECRET); assertArrayEquals(SECRET, store.load("test").orElseThrow()); } }
    @Test void overwrite() { for (var store: stores) { store.save("test", SECRET); store.save("test", new byte[]{0,1,2}); assertArrayEquals(new byte[]{0,1,2}, store.load("test").orElseThrow()); } }
    @Test void delete() { for (var store: stores) { store.save("test", SECRET); store.delete("test"); assertTrue(store.load("test").isEmpty()); } }
    @Test void missing() { for (var store: stores) { assertTrue(store.load("missing").isEmpty()); store.delete("missing"); } }
    @Test void invalidKeys() { for (var store: stores) for (String key: List.of("", " ", "../escape", "a/b", "a\\b", "a\n", "a".repeat(129))) {
        assertThrows(IllegalArgumentException.class, () -> store.save(key, SECRET));
        assertThrows(IllegalArgumentException.class, () -> store.load(key));
        assertThrows(IllegalArgumentException.class, () -> store.delete(key));
    } }
    @Test void invalidSecrets() { for (var store: stores) { assertThrows(IllegalArgumentException.class, () -> store.save("test", new byte[0])); assertThrows(NullPointerException.class, () -> store.save("test", null)); } }
    @Test void callersRetainTheirInput() { for (var store: stores) { byte[] value=SECRET.clone(); store.save("test",value); assertArrayEquals(SECRET,value); } }
    @Test void returnedValuesAreIndependent() { for (var store: stores) { store.save("test",SECRET); Arrays.fill(store.load("test").orElseThrow(),(byte)0); assertArrayEquals(SECRET,store.load("test").orElseThrow()); } }
    @Test void toStringContainsNoSecretOrKey() { for (var store: stores) { store.save("private-key",SECRET); assertFalse(store.toString().contains("private-key")); assertFalse(store.toString().contains(new String(SECRET))); } }
    @Test void nativeUnavailableIsNeverMissing() {
        var store = new LinuxSecretStore(new NativeSecretProvider() {
            private void fail() { throw new SecretStoreException(SecretStoreException.Kind.UNAVAILABLE); }
            public void save(String k, byte[] v) { fail(); }
            public Optional<byte[]> load(String k) { fail(); return Optional.empty(); }
            public void delete(String k) { fail(); }
        });
        assertEquals(SecretStoreException.Kind.UNAVAILABLE, assertThrows(SecretStoreException.class, () -> store.load("test")).kind());
        assertThrows(SecretStoreException.class, () -> store.save("test",SECRET));
        assertThrows(SecretStoreException.class, () -> store.delete("test"));
    }
    @Test void protectorErrorsAreSanitized() {
        var store = new WindowsSecretStore(directory, new SecretProtector() {
            public byte[] protect(byte[] bytes) { throw new RuntimeException(new String(SECRET)); }
            public byte[] unprotect(byte[] bytes) { throw new RuntimeException(new String(SECRET)); }
        });
        var error = assertThrows(SecretStoreException.class, () -> store.save("test", SECRET));
        assertFalse(error.toString().contains(new String(SECRET))); assertNull(error.getCause());
    }
    @Test void windowsPersistsOnlyProtectedBytes() throws Exception {
        var store=stores.getFirst(); store.save("test", SECRET);
        try (var files=java.nio.file.Files.list(directory)) {
            var file=files.findFirst().orElseThrow();
            assertFalse(file.getFileName().toString().contains("test"));
            assertFalse(Arrays.equals(SECRET, java.nio.file.Files.readAllBytes(file)));
        }
    }
    @Test void macOsIsExplicitlyUnavailable() {
        var store = new MacOsSecretStore();
        assertEquals(SecretStoreException.Kind.UNAVAILABLE, assertThrows(SecretStoreException.class, () -> store.load("test")).kind());
        assertThrows(SecretStoreException.class, () -> store.save("test",SECRET));
        assertThrows(SecretStoreException.class, () -> store.delete("test"));
        assertTrue(store.toString().contains("pending"));
    }
    @Test void unknownFactoryFailsSecure() {
        assertThrows(SecretStoreException.class, () -> SecretStoreFactory.create(br.com.lucraone.pdv.infrastructure.platform.OperatingSystem.UNSUPPORTED, null));
    }
    @Test void windowsReadErrorsAreDistinctFromMissing() throws Exception {
        var store = stores.getFirst(); store.save("test", SECRET);
        try (var files=java.nio.file.Files.list(directory)) {
            java.nio.file.Files.write(files.findFirst().orElseThrow(), new byte[0]);
        }
        assertEquals(SecretStoreException.Kind.READ_FAILED, assertThrows(SecretStoreException.class, () -> store.load("test")).kind());
    }
    @Test void windowsWriteErrorsAreSanitized() throws Exception {
        Path blocked=directory.resolve("blocked"); java.nio.file.Files.write(blocked, new byte[]{1});
        var store=new WindowsSecretStore(blocked, new SecretProtector() {
            public byte[] protect(byte[] input) { return new byte[]{1}; }
            public byte[] unprotect(byte[] input) { return new byte[]{1}; }
        });
        assertEquals(SecretStoreException.Kind.WRITE_FAILED, assertThrows(SecretStoreException.class, () -> store.save("test",SECRET)).kind());
    }
    @Test void nullKeysAreRejected() { for (var store: stores) {
        assertThrows(IllegalArgumentException.class, () -> store.save(null,SECRET));
        assertThrows(IllegalArgumentException.class, () -> store.load(null));
        assertThrows(IllegalArgumentException.class, () -> store.delete(null));
    } }
    @Test void largeSecretsAreRejected() { for (var store: stores) assertThrows(IllegalArgumentException.class, () -> store.save("test",new byte[1024*1024+1])); }
    @Test void macOsValidatesWithoutPretendingToStore() {
        var store=new MacOsSecretStore();
        assertThrows(IllegalArgumentException.class, () -> store.save("../invalid",SECRET));
        assertThrows(IllegalArgumentException.class, () -> store.load("../invalid"));
        assertThrows(IllegalArgumentException.class, () -> store.delete("../invalid"));
    }
    @Test void macOsFactorySelectsExplicitBoundary() {
        assertInstanceOf(MacOsSecretStore.class, SecretStoreFactory.create(br.com.lucraone.pdv.infrastructure.platform.OperatingSystem.MACOS,null));
    }
    @Test void nativeErrorsNeverPropagateProviderMessages() {
        var store=new LinuxSecretStore(new NativeSecretProvider() {
            public void save(String k,byte[] v) { throw new IllegalStateException(new String(SECRET)); }
            public Optional<byte[]> load(String k) { throw new IllegalStateException(new String(SECRET)); }
            public void delete(String k) { throw new IllegalStateException(new String(SECRET)); }
        });
        for (Runnable operation: List.<Runnable>of(() -> store.save("test",SECRET), () -> store.load("test"), () -> store.delete("test"))) {
            var error=assertThrows(SecretStoreException.class,operation::run);
            assertFalse(error.toString().contains(new String(SECRET))); assertNull(error.getCause());
        }
    }
}
