package br.com.lucraone.pdv.infrastructure.persistence;

import br.com.lucraone.pdv.application.terminal.TerminalRepository;
import br.com.lucraone.pdv.domain.terminal.Installation;
import br.com.lucraone.pdv.domain.terminal.TerminalProvisioning;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores the installation identity and terminal provisioning in single-row SQLite tables. Timestamps are
 * persisted as UTC ISO-8601 text.
 */
public final class JdbcTerminalRepository implements TerminalRepository {

    private final JdbcTransactionManager transactions;

    public JdbcTerminalRepository(JdbcTransactionManager transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    }

    @Override
    public Installation ensureInstallation(Installation candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");

        return transactions.inTransaction((connection, transaction) -> {
            // Only a concurrent or previous insert of the single row is ignored; other violations still fail.
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO installation (singleton, installation_id, created_at)
                    VALUES (1, ?, ?)
                    ON CONFLICT (singleton) DO NOTHING
                    """)) {
                insert.setString(1, candidate.installationId().toString());
                insert.setString(2, candidate.createdAt().toString());
                insert.executeUpdate();
            }
            return findInstallation(connection)
                    .orElseThrow(() -> new LocalDatabaseException("A identidade da instalação não foi persistida."));
        });
    }

    @Override
    public Optional<Installation> findInstallation() {
        return transactions.inTransaction((connection, transaction) -> findInstallation(connection));
    }

    @Override
    public Optional<TerminalProvisioning> findProvisioning() {
        return transactions.inTransaction((connection, transaction) -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT terminal_id, branch_id, provisioned_at FROM terminal_provisioning WHERE singleton = 1");
                 ResultSet result = select.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new TerminalProvisioning(
                        result.getString("terminal_id"),
                        result.getString("branch_id"),
                        Instant.parse(result.getString("provisioned_at"))
                ));
            }
        });
    }

    @Override
    public void saveProvisioning(TerminalProvisioning provisioning) {
        Objects.requireNonNull(provisioning, "provisioning must not be null");

        transactions.inTransaction((connection, transaction) -> {
            Installation installation = findInstallation(connection)
                    .orElseThrow(() -> new IllegalStateException("The installation must exist before provisioning."));

            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO terminal_provisioning (singleton, installation_id, terminal_id, branch_id, provisioned_at)
                    VALUES (1, ?, ?, ?, ?)
                    ON CONFLICT (singleton) DO UPDATE SET
                        installation_id = excluded.installation_id,
                        terminal_id = excluded.terminal_id,
                        branch_id = excluded.branch_id,
                        provisioned_at = excluded.provisioned_at
                    """)) {
                upsert.setString(1, installation.installationId().toString());
                upsert.setString(2, provisioning.terminalId());
                upsert.setString(3, provisioning.branchId());
                upsert.setString(4, provisioning.provisionedAt().toString());
                upsert.executeUpdate();
            }
            return null;
        });
    }

    private Optional<Installation> findInstallation(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT installation_id, created_at FROM installation WHERE singleton = 1");
             ResultSet result = select.executeQuery()) {
            if (!result.next()) {
                return Optional.empty();
            }
            return Optional.of(new Installation(
                    parseInstallationId(result.getString("installation_id")),
                    Instant.parse(result.getString("created_at"))
            ));
        }
    }

    private static UUID parseInstallationId(String value) {
        // UUID.fromString accepts non-canonical forms, so the stored text must round-trip exactly.
        UUID installationId = UUID.fromString(value);
        if (!installationId.toString().equals(value)) {
            throw new LocalDatabaseException("O installation_id armazenado não está na forma canônica.");
        }
        return installationId;
    }
}
