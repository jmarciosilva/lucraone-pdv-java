package br.com.lucraone.pdv.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcTransactionManagerTest {

    @TempDir
    Path temporaryDirectory;

    private SqliteConnectionFactory connections;
    private JdbcTransactionManager transactions;

    @BeforeEach
    void setUp() throws SQLException {
        LocalDataDirectory directory = new LocalDataDirectory(() -> temporaryDirectory.toString());
        connections = new LocalDatabaseInitializer(directory).initialize();
        transactions = new JdbcTransactionManager(connections);

        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE transaction_probe (value TEXT NOT NULL)");
        }
    }

    @Test
    void commitsSuccessfulWork() throws Exception {
        transactions.inTransaction((connection, transaction) -> {
            insert(connection, "committed");
            return null;
        });

        assertEquals(1, countRows());
    }

    @Test
    void rollsBackWorkMarkedAsRollbackOnly() throws Exception {
        transactions.inTransaction((connection, transaction) -> {
            insert(connection, "rolled-back");
            transaction.markRollbackOnly();
            return null;
        });

        assertEquals(0, countRows());
    }

    @Test
    void rollsBackWhenTheCallbackFails() throws SQLException {
        assertThrows(IllegalStateException.class, () -> transactions.inTransaction((connection, transaction) -> {
            insert(connection, "failed");
            throw new IllegalStateException("failure during transaction");
        }));

        assertEquals(0, countRows());
    }

    @Test
    void rollsBackAndWrapsSqlFailures() throws SQLException {
        LocalDatabaseException exception = assertThrows(
                LocalDatabaseException.class,
                () -> transactions.inTransaction((connection, transaction) -> {
                    insert(connection, "before-sql-failure");
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("INSERT INTO missing_table(value) VALUES ('x')");
                    }
                    return null;
                })
        );

        assertInstanceOf(SQLException.class, exception.getCause());
        assertEquals(0, countRows());
    }

    @Test
    void closesTheConnectionAfterTheTransaction() throws Exception {
        AtomicReference<Connection> usedConnection = new AtomicReference<>();

        transactions.inTransaction((connection, transaction) -> {
            usedConnection.set(connection);
            return null;
        });

        assertTrue(usedConnection.get().isClosed());
    }

    private void insert(Connection connection, String value) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO transaction_probe(value) VALUES (?)")) {
            statement.setString(1, value);
            statement.executeUpdate();
        }
    }

    private int countRows() throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM transaction_probe")) {
            result.next();
            return result.getInt(1);
        }
    }
}
