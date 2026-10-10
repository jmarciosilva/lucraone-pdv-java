package br.com.lucraone.pdv.infrastructure.security;

import br.com.lucraone.pdv.application.security.*;
import java.util.*;

public final class LinuxSecretStore implements SecretStore {
    private final NativeSecretProvider provider;
    public LinuxSecretStore() { this(new LibSecretProvider()); }
    LinuxSecretStore(NativeSecretProvider provider) { this.provider = Objects.requireNonNull(provider); }
    public void save(String key, byte[] secret) {
        SecretStoreValidation.key(key); SecretStoreValidation.secret(secret);
        byte[] copy = secret.clone();
        try { provider.save(key, copy); }
        catch (SecretStoreException e) { throw new SecretStoreException(e.kind()); }
        catch (RuntimeException e) { throw new SecretStoreException(SecretStoreException.Kind.WRITE_FAILED); }
        finally { Arrays.fill(copy, (byte) 0); }
    }
    public Optional<byte[]> load(String key) {
        SecretStoreValidation.key(key);
        try { return provider.load(key); }
        catch (SecretStoreException e) { throw new SecretStoreException(e.kind()); }
        catch (RuntimeException e) { throw new SecretStoreException(SecretStoreException.Kind.READ_FAILED); }
    }
    public void delete(String key) {
        SecretStoreValidation.key(key);
        try { provider.delete(key); }
        catch (SecretStoreException e) { throw new SecretStoreException(e.kind()); }
        catch (RuntimeException e) { throw new SecretStoreException(SecretStoreException.Kind.DELETE_FAILED); }
    }
    public String toString() { return "LinuxSecretStore[native]"; }
}
