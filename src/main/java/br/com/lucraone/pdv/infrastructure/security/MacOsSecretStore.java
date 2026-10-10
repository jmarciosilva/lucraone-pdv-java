package br.com.lucraone.pdv.infrastructure.security;

import br.com.lucraone.pdv.application.security.*;
import java.util.Optional;

/** Explicit fail-secure boundary, NOT a Keychain implementation. Pending native macOS validation.
 * The security CLI is not used because supplying a password via argv exposes it to process inspection.
 */
public final class MacOsSecretStore implements SecretStore {
    public void save(String key, byte[] secret) {
        SecretStoreValidation.key(key); SecretStoreValidation.secret(secret); unavailable();
    }
    public Optional<byte[]> load(String key) { SecretStoreValidation.key(key); unavailable(); return Optional.empty(); }
    public void delete(String key) { SecretStoreValidation.key(key); unavailable(); }
    private static void unavailable() { throw new SecretStoreException(SecretStoreException.Kind.UNAVAILABLE); }
    public String toString() { return "MacOsSecretStore[pending native integration]"; }
}
