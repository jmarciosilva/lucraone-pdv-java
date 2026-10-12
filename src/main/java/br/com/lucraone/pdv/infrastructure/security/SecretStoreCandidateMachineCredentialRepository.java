package br.com.lucraone.pdv.infrastructure.security;

import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.auth.CandidateMachineCredentialRepository;
import br.com.lucraone.pdv.application.auth.CredentialStorageException;
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

/** Rotation candidate credential uses a separate key to avoid overwriting the current machine credential. */
public final class SecretStoreCandidateMachineCredentialRepository implements CandidateMachineCredentialRepository {

    private static final String DEFAULT_KEY = "machine-credential-candidate-v1";
    private static final int MAX_ENVELOPE_BYTES = 1024 * 1024;
    private static final Set<String> FIELDS = Set.of("version", "generation", "token_type", "access_token", "expires_at");

    private final SecretStore store;
    private final String key;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public SecretStoreCandidateMachineCredentialRepository(SecretStore store) {
        this(store, DEFAULT_KEY);
    }

    /** Allows independent protected slots for rotation and administrative recovery candidates. */
    public SecretStoreCandidateMachineCredentialRepository(SecretStore store, String key) {
        this.store = Objects.requireNonNull(store);
        if (key == null || !key.matches("[a-z0-9-]{1,80}")) throw new IllegalArgumentException("Invalid secret-store key.");
        this.key = key;
    }

    @Override
    public void save(MachineCredential candidate) {
        Objects.requireNonNull(candidate);
        validateBearer(candidate);
        byte[] bytes = null;
        try {
            var payload = mapper.createObjectNode();
            payload.put("version", 1);
            payload.put("generation", UUID.randomUUID().toString());
            payload.put("token_type", candidate.tokenType());
            payload.put("access_token", candidate.accessToken());
            payload.put("expires_at", candidate.expiresAt().toString());
            bytes = mapper.writeValueAsBytes(payload);
            if (bytes.length > MAX_ENVELOPE_BYTES) {
                throw new CredentialStorageException(CredentialStorageException.Kind.WRITE_FAILED);
            }
            store.save(key, bytes);
        } catch (SecretStoreException error) {
            throw map(error, CredentialStorageException.Kind.WRITE_FAILED);
        } catch (CredentialStorageException error) {
            throw error;
        } catch (Exception error) {
            throw new CredentialStorageException(CredentialStorageException.Kind.WRITE_FAILED);
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public Optional<StoredMachineCredential> load() {
        byte[] bytes;
        try {
            Optional<byte[]> loaded = store.load(key);
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
            if (payload == null || !payload.isObject() || payload.size() != FIELDS.size()) throw new IllegalArgumentException();
            for (String field : FIELDS) { if (!payload.has(field)) throw new IllegalArgumentException(); }
            JsonNode version = payload.get("version");
            if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1) throw new IllegalArgumentException();
            String rawGeneration = text(payload, "generation");
            UUID generation = UUID.fromString(rawGeneration);
            if (!generation.toString().equals(rawGeneration)) throw new IllegalArgumentException();
            MachineCredential credential = new MachineCredential(text(payload, "token_type"),
                    text(payload, "access_token"), Instant.parse(text(payload, "expires_at")));
            validateBearer(credential);
            return Optional.of(new StoredMachineCredential(generation, credential));
        } catch (Exception error) {
            throw new CredentialStorageException(CredentialStorageException.Kind.INVALID_PAYLOAD);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public void delete() {
        try {
            store.delete(key);
        } catch (SecretStoreException error) {
            throw map(error, CredentialStorageException.Kind.DELETE_FAILED);
        } catch (RuntimeException error) {
            throw new CredentialStorageException(CredentialStorageException.Kind.DELETE_FAILED);
        }
    }

    private static String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (!node.isTextual()) throw new IllegalArgumentException();
        return node.textValue();
    }

    private static void validateBearer(MachineCredential credential) {
        if (!"Bearer".equals(credential.tokenType()) || credential.accessToken().isBlank()
                || credential.accessToken().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid candidate credential.");
        }
    }

    private static CredentialStorageException map(SecretStoreException error, CredentialStorageException.Kind fallback) {
        return new CredentialStorageException(error.kind() == SecretStoreException.Kind.UNAVAILABLE
                ? CredentialStorageException.Kind.UNAVAILABLE : fallback);
    }

    @Override public String toString() { return "SecretStoreCandidateMachineCredentialRepository[protected]"; }
}
