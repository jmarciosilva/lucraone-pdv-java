package br.com.lucraone.pdv.infrastructure.security;

import br.com.lucraone.pdv.application.auth.CredentialRotationProofRepository;
import br.com.lucraone.pdv.application.auth.CredentialStorageException;
import br.com.lucraone.pdv.application.security.SecretStore;
import br.com.lucraone.pdv.application.security.SecretStoreException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Rotation retry proof is protected by Secret Service and never written to SQLite or logs. */
public final class SecretStoreCredentialRotationProofRepository implements CredentialRotationProofRepository {
    private static final String KEY = "machine-credential-rotation-proof-v1";
    private static final Set<String> FIELDS = Set.of("version", "operation_id", "delivery_secret");
    private final SecretStore store;
    private final ObjectMapper mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public SecretStoreCredentialRotationProofRepository(SecretStore store) { this.store = Objects.requireNonNull(store); }

    @Override public void save(Proof proof) {
        Objects.requireNonNull(proof);
        validate(proof);
        byte[] bytes = null;
        try {
            var node = mapper.createObjectNode();
            node.put("version", 1); node.put("operation_id", proof.operationId());
            node.put("delivery_secret", proof.deliverySecret());
            bytes = mapper.writeValueAsBytes(node); store.save(KEY, bytes);
        } catch (SecretStoreException error) { throw map(error, CredentialStorageException.Kind.WRITE_FAILED); }
        catch (CredentialStorageException error) { throw error; }
        catch (Exception error) { throw new CredentialStorageException(CredentialStorageException.Kind.WRITE_FAILED); }
        finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }

    @Override public Optional<Proof> load() {
        byte[] bytes;
        try { Optional<byte[]> value = store.load(KEY); if (value.isEmpty()) return Optional.empty(); bytes = value.orElseThrow(); }
        catch (SecretStoreException error) { throw map(error, CredentialStorageException.Kind.READ_FAILED); }
        catch (RuntimeException error) { throw new CredentialStorageException(CredentialStorageException.Kind.READ_FAILED); }
        try {
            JsonNode node = mapper.readTree(bytes);
            if (node == null || !node.isObject() || node.size() != FIELDS.size()) throw new IllegalArgumentException();
            for (String field : FIELDS) if (!node.has(field)) throw new IllegalArgumentException();
            if (!node.get("version").isInt() || node.get("version").intValue() != 1) throw new IllegalArgumentException();
            Proof proof = new Proof(text(node, "operation_id"), text(node, "delivery_secret"));
            validate(proof);
            return Optional.of(proof);
        } catch (Exception error) { throw new CredentialStorageException(CredentialStorageException.Kind.INVALID_PAYLOAD); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    @Override public void delete() {
        try { store.delete(KEY); }
        catch (SecretStoreException error) { throw map(error, CredentialStorageException.Kind.DELETE_FAILED); }
        catch (RuntimeException error) { throw new CredentialStorageException(CredentialStorageException.Kind.DELETE_FAILED); }
    }

    private static String text(JsonNode node, String field) {
        if (!node.get(field).isTextual()) throw new IllegalArgumentException();
        return node.get(field).textValue();
    }
    private static void validate(Proof proof) {
        try {
            String id = proof.operationId();
            UUID parsed = UUID.fromString(id);
            if (!parsed.toString().equals(id) || !proof.deliverySecret().matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
        } catch (RuntimeException error) { throw new CredentialStorageException(CredentialStorageException.Kind.INVALID_PAYLOAD); }
    }
    private static CredentialStorageException map(SecretStoreException error, CredentialStorageException.Kind fallback) {
        return new CredentialStorageException(error.kind() == SecretStoreException.Kind.UNAVAILABLE
                ? CredentialStorageException.Kind.UNAVAILABLE : fallback);
    }
    @Override public String toString() { return "SecretStoreCredentialRotationProofRepository[redacted]"; }
}
