package br.com.lucraone.pdv.infrastructure.security;

import br.com.lucraone.pdv.application.auth.CredentialRecoveryRepository;
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

/** Strict, redacted storage of recovery code and delivery proof; never persisted outside Secret Service. */
public final class SecretStoreCredentialRecoveryRepository implements CredentialRecoveryRepository {
    private static final String KEY = "machine-credential-recovery-proof-v1";
    private static final Set<String> FIELDS = Set.of("version", "recovery_code", "installation_id", "delivery_secret", "operation_id");
    private final SecretStore store;
    private final ObjectMapper mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public SecretStoreCredentialRecoveryRepository(SecretStore store) { this.store = Objects.requireNonNull(store); }

    @Override public void save(Proof proof) {
        Objects.requireNonNull(proof);
        if (proof.recoveryCode().length() != 19 || !proof.deliverySecret().matches("[a-f0-9]{64}"))
            throw new CredentialStorageException(CredentialStorageException.Kind.INVALID_PAYLOAD);
        byte[] bytes = null;
        try {
            var node = mapper.createObjectNode();
            node.put("version", 1); node.put("recovery_code", proof.recoveryCode());
            node.put("installation_id", proof.installationId().toString()); node.put("delivery_secret", proof.deliverySecret());
            if (proof.operationId() == null) node.putNull("operation_id"); else node.put("operation_id", proof.operationId());
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
            UUID installation = UUID.fromString(text(node, "installation_id"));
            if (!installation.toString().equals(text(node, "installation_id"))) throw new IllegalArgumentException();
            String code = text(node, "recovery_code"), secret = text(node, "delivery_secret");
            if (code.length() != 19 || !secret.matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
            JsonNode op = node.get("operation_id");
            if (!op.isNull() && !op.isTextual()) throw new IllegalArgumentException();
            return Optional.of(new Proof(code, installation, secret, op.isNull() ? null : op.textValue()));
        } catch (Exception error) { throw new CredentialStorageException(CredentialStorageException.Kind.INVALID_PAYLOAD); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    @Override public void delete() {
        try { store.delete(KEY); }
        catch (SecretStoreException error) { throw map(error, CredentialStorageException.Kind.DELETE_FAILED); }
        catch (RuntimeException error) { throw new CredentialStorageException(CredentialStorageException.Kind.DELETE_FAILED); }
    }

    private static String text(JsonNode node, String field) { if (!node.get(field).isTextual()) throw new IllegalArgumentException(); return node.get(field).textValue(); }
    private static CredentialStorageException map(SecretStoreException error, CredentialStorageException.Kind fallback) {
        return new CredentialStorageException(error.kind() == SecretStoreException.Kind.UNAVAILABLE ? CredentialStorageException.Kind.UNAVAILABLE : fallback);
    }
    @Override public String toString() { return "SecretStoreCredentialRecoveryRepository[redacted]"; }
}
