package br.com.lucraone.pdv.infrastructure.security;

import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.auth.CredentialStorageException;
import br.com.lucraone.pdv.application.auth.MachineCredentialRepository;
import br.com.lucraone.pdv.application.auth.StoredMachineCredential;
import br.com.lucraone.pdv.application.security.SecretStore;
import br.com.lucraone.pdv.application.security.SecretStoreException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Versioned JSON exists only in memory and inside the native protected store. */
public final class SecretStoreMachineCredentialRepository implements MachineCredentialRepository {
    private static final String KEY = "machine-credential-v1";
    private static final int MAX_ENVELOPE_BYTES = 1024 * 1024;
    private static final Set<String> FIELDS = Set.of("version", "generation", "token_type", "access_token", "expires_at");
    private final SecretStore store;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public SecretStoreMachineCredentialRepository(SecretStore store) {
        this.store = Objects.requireNonNull(store);
    }

    @Override public void save(StoredMachineCredential value) {
        Objects.requireNonNull(value);
        MachineCredential credential = value.credential();
        validate(credential);
        byte[] bytes = null;
        try {
            var payload = mapper.createObjectNode();
            payload.put("version", 1);
            payload.put("generation", value.generation().toString());
            payload.put("token_type", credential.tokenType());
            payload.put("access_token", credential.accessToken());
            payload.put("expires_at", credential.expiresAt().toString());
            bytes = mapper.writeValueAsBytes(payload);
            if (bytes.length > MAX_ENVELOPE_BYTES) {
                throw new CredentialStorageException(CredentialStorageException.Kind.WRITE_FAILED);
            }
            store.save(KEY, bytes);
        } catch (SecretStoreException error) {
            throw map(error, CredentialStorageException.Kind.WRITE_FAILED);
        } catch (Exception error) {
            throw new CredentialStorageException(CredentialStorageException.Kind.WRITE_FAILED);
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override public Optional<StoredMachineCredential> load() {
        byte[] bytes;
        try {
            Optional<byte[]> loaded = store.load(KEY);
            if (loaded.isEmpty()) return Optional.empty();
            bytes = loaded.orElseThrow();
        } catch (SecretStoreException error) {
            throw map(error, CredentialStorageException.Kind.READ_FAILED);
        } catch (RuntimeException error) {
            throw new CredentialStorageException(CredentialStorageException.Kind.READ_FAILED);
        }
        try {
            if (bytes.length == 0 || bytes.length > MAX_ENVELOPE_BYTES) throw new IllegalArgumentException();
            JsonNode payload = mapper.readTree(bytes);
            if (payload == null || !payload.isObject() || payload.size() != FIELDS.size()) {
                throw new IllegalArgumentException();
            }
            for (String field : FIELDS) {
                if (!payload.has(field)) throw new IllegalArgumentException();
            }
            JsonNode version = payload.get("version");
            if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1) {
                throw new IllegalArgumentException();
            }
            String rawGeneration = text(payload, "generation");
            UUID generation = UUID.fromString(rawGeneration);
            if (!generation.toString().equals(rawGeneration)) throw new IllegalArgumentException();
            MachineCredential credential = new MachineCredential(text(payload, "token_type"),
                    text(payload, "access_token"), Instant.parse(text(payload, "expires_at")));
            validate(credential);
            return Optional.of(new StoredMachineCredential(generation, credential));
        } catch (Exception error) {
            // Jackson and parsing causes can quote the secret payload: never retain them.
            throw new CredentialStorageException(CredentialStorageException.Kind.INVALID_PAYLOAD);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override public void delete() {
        try {
            store.delete(KEY);
        } catch (SecretStoreException error) {
            throw map(error, CredentialStorageException.Kind.DELETE_FAILED);
        } catch (RuntimeException error) {
            throw new CredentialStorageException(CredentialStorageException.Kind.DELETE_FAILED);
        }
    }

    @Override public void verifyWritable() {
        String key = "machine-credential-readiness-" + UUID.randomUUID();
        byte[] probe = ("lucraone-test-secret-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        byte[] loaded = null;
        try {
            store.save(key, probe);
            loaded = store.load(key).orElseThrow();
            if (!Arrays.equals(probe, loaded)) {
                throw new CredentialStorageException(CredentialStorageException.Kind.UNAVAILABLE);
            }
        } catch (Exception error) {
            throw new CredentialStorageException(CredentialStorageException.Kind.UNAVAILABLE);
        } finally {
            Arrays.fill(probe, (byte) 0);
            if (loaded != null) Arrays.fill(loaded, (byte) 0);
            try {
                store.delete(key);
                Optional<byte[]> remaining = store.load(key);
                if (remaining.isPresent()) {
                    Arrays.fill(remaining.orElseThrow(), (byte) 0);
                    throw new IllegalStateException();
                }
            } catch (Exception error) {
                throw new CredentialStorageException(CredentialStorageException.Kind.UNAVAILABLE);
            }
        }
    }

    private static String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (!node.isTextual()) throw new IllegalArgumentException();
        return node.textValue();
    }

    private static void validate(MachineCredential credential) {
        if (!"Bearer".equals(credential.tokenType()) || credential.accessToken().isBlank()
                || credential.accessToken().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid machine credential.");
        }
    }

    private static CredentialStorageException map(SecretStoreException error, CredentialStorageException.Kind fallback) {
        return new CredentialStorageException(error.kind() == SecretStoreException.Kind.UNAVAILABLE
                ? CredentialStorageException.Kind.UNAVAILABLE : fallback);
    }

    @Override public String toString() { return "SecretStoreMachineCredentialRepository[protected]"; }
}
