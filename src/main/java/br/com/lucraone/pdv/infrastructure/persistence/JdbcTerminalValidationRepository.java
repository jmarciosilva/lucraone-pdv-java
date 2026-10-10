package br.com.lucraone.pdv.infrastructure.persistence;

import br.com.lucraone.pdv.application.auth.TerminalValidation;
import br.com.lucraone.pdv.application.auth.TerminalValidationRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Stores only evidence of a coherent online validation, never a token or token digest. */
public final class JdbcTerminalValidationRepository implements TerminalValidationRepository {
    private final JdbcTransactionManager transactions;

    public JdbcTerminalValidationRepository(JdbcTransactionManager transactions) {
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override public Optional<TerminalValidation> load() {
        return transactions.inTransaction((connection, transaction) -> {
            try (var statement = connection.prepareStatement("SELECT * FROM terminal_validation WHERE singleton = 1");
                 var result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                try {
                    return Optional.of(new TerminalValidation(
                            canonicalUuid(result.getString("credential_generation")),
                            canonicalUuid(result.getString("installation_id")),
                            result.getString("terminal_id"), result.getString("branch_id"),
                            result.getString("backend_identity"), Instant.parse(result.getString("validated_at"))));
                } catch (RuntimeException invalid) {
                    throw new LocalDatabaseException("A evidência local de autenticação é inválida.");
                }
            }
        });
    }

    @Override public void save(TerminalValidation validation) {
        Objects.requireNonNull(validation);
        transactions.inTransaction((connection, transaction) -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO terminal_validation (singleton, credential_generation, installation_id,
                        terminal_id, branch_id, backend_identity, validated_at)
                    VALUES (1, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (singleton) DO UPDATE SET
                        credential_generation = excluded.credential_generation,
                        installation_id = excluded.installation_id,
                        terminal_id = excluded.terminal_id, branch_id = excluded.branch_id,
                        backend_identity = excluded.backend_identity, validated_at = excluded.validated_at
                    """)) {
                statement.setString(1, validation.generation().toString());
                statement.setString(2, validation.installationId().toString());
                statement.setString(3, validation.terminalId());
                statement.setString(4, validation.branchId());
                statement.setString(5, validation.backendIdentity());
                statement.setString(6, validation.validatedAt().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override public void invalidate() {
        transactions.inTransaction((connection, transaction) -> {
            try (var statement = connection.prepareStatement("DELETE FROM terminal_validation WHERE singleton = 1")) {
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static UUID canonicalUuid(String value) {
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value)) throw new IllegalArgumentException("Invalid UUID");
        return uuid;
    }
}
