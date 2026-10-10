package br.com.lucraone.pdv.infrastructure.security;

import java.util.Optional;

/** Internal native boundary, injectable for deterministic tests. */
interface NativeSecretProvider {
    void save(String key, byte[] secret);
    Optional<byte[]> load(String key);
    void delete(String key);
}
