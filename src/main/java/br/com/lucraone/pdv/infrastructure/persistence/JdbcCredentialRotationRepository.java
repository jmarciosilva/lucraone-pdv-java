package br.com.lucraone.pdv.infrastructure.persistence;

import br.com.lucraone.pdv.application.auth.CredentialRotationRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class JdbcCredentialRotationRepository implements CredentialRotationRepository {

    private final JdbcTransactionManager transactions;

    public JdbcCredentialRotationRepository(JdbcTransactionManager transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    }

    @Override
    public void save(PendingRotation rotation) {
        Objects.requireNonNull(rotation, "rotation must not be null");
        transactions.inTransaction((connection, transaction) -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO credential_rotation (singleton, operation_id, started_at)
                    VALUES (1, ?, ?)
                    ON CONFLICT (singleton) DO UPDATE SET
                        operation_id = excluded.operation_id,
                        started_at   = excluded.started_at
                    """)) {
                upsert.setString(1, rotation.operationId());
                upsert.setString(2, rotation.startedAt().toString());
                upsert.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<PendingRotation> load() {
        return transactions.inTransaction((connection, transaction) -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT operation_id, started_at FROM credential_rotation WHERE singleton = 1");
                 ResultSet result = select.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PendingRotation(
                        result.getString("operation_id"),
                        Instant.parse(result.getString("started_at"))
                ));
            }
        });
    }

    @Override
    public void delete() {
        transactions.inTransaction((connection, transaction) -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM credential_rotation WHERE singleton = 1")) {
                delete.executeUpdate();
            }
            return null;
        });
    }
}
