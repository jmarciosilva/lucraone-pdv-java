package br.com.lucraone.pdv.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDatabaseInitializerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsMigratesAndConfiguresANewTemporaryDatabase() throws Exception {
        LocalDataDirectory directory = new LocalDataDirectory(() -> temporaryDirectory.toString());
        SqliteConnectionFactory connections = new LocalDatabaseInitializer(directory).initialize();

        assertTrue(directory.databasePath().startsWith(temporaryDirectory));
        assertTrue(Files.exists(directory.databasePath()));

        try (Connection connection = connections.openConnection()) {
            assertEquals(1, queryInteger(connection, "PRAGMA foreign_keys"));
            assertEquals(5_000, queryInteger(connection, "PRAGMA busy_timeout"));
            assertEquals("wal", queryString(connection, "PRAGMA journal_mode"));
            assertEquals(1, queryInteger(
                    connection,
                    "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE version = '1' AND success = 1"
            ));
        }
    }

    @Test
    void reopensAnAlreadyMigratedDatabaseWithoutRepeatingTheMigration() throws Exception {
        LocalDataDirectory directory = new LocalDataDirectory(() -> temporaryDirectory.toString());
        LocalDatabaseInitializer initializer = new LocalDatabaseInitializer(directory);

        initializer.initialize();
        SqliteConnectionFactory connections = initializer.initialize();

        try (Connection connection = connections.openConnection()) {
            assertEquals(1, queryInteger(
                    connection,
                    "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE version = '1'"
            ));
        }
    }

    @Test
    void preservesAnUnknownExistingDatabaseWhenMigrationIsRefused() throws Exception {
        LocalDataDirectory directory = new LocalDataDirectory(() -> temporaryDirectory.toString());
        directory.prepareOperationalDirectories();
        SqliteConnectionFactory connections = new SqliteConnectionFactory(directory.databasePath());
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE preexisting_probe (value TEXT NOT NULL)");
            statement.execute("INSERT INTO preexisting_probe(value) VALUES ('kept')");
        }

        LocalDatabaseException exception = assertThrows(
                LocalDatabaseException.class,
                () -> new LocalDatabaseInitializer(directory).initialize()
        );

        assertInstanceOf(FlywayException.class, exception.getCause());

        assertTrue(Files.exists(directory.databasePath()));
        try (Connection connection = connections.openConnection()) {
            assertEquals(1, queryInteger(connection, "SELECT COUNT(*) FROM preexisting_probe"));
        }
    }

    private int queryInteger(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
