package br.com.lucraone.pdv.application.security;

import java.util.Optional;

/** Native protected storage. Keys are non-secret identifiers. Calls may block: never use on the UI thread.
 * Input remains caller-owned; load returns a caller-owned array which must be wiped after use.
 * Only a successfully queried absent key returns empty; unavailable providers throw.
 */
public interface SecretStore {
    void save(String key, byte[] secret);
    Optional<byte[]> load(String key);
    void delete(String key);
}
