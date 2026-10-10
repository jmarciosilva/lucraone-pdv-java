package br.com.lucraone.pdv.infrastructure.security;

import br.com.lucraone.pdv.application.security.SecretStore;
import br.com.lucraone.pdv.application.security.SecretStoreException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Defers native initialization to the worker. Initialization failure remains UNAVAILABLE, never missing. */
public final class LazySecretStore implements SecretStore {
    private final Supplier<SecretStore> factory;
    private SecretStore delegate;
    public LazySecretStore(Supplier<SecretStore> factory) { this.factory = Objects.requireNonNull(factory); }
    private synchronized SecretStore delegate() {
        if (delegate == null) {
            try { delegate = Objects.requireNonNull(factory.get()); }
            catch (RuntimeException | LinkageError error) {
                throw new SecretStoreException(SecretStoreException.Kind.UNAVAILABLE);
            }
        }
        return delegate;
    }
    @Override public void save(String key, byte[] secret) { delegate().save(key, secret); }
    @Override public Optional<byte[]> load(String key) { return delegate().load(key); }
    @Override public void delete(String key) { delegate().delete(key); }
    @Override public String toString() { return "LazySecretStore[protected]"; }
}
