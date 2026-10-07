package br.com.lucraone.pdv.infrastructure.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Opens configured SQLite connections for the local database of one terminal.
 */
public final class SqliteConnectionFactory {

    private static final int BUSY_TIMEOUT_MILLIS = 5_000;

    private final Path databasePath;

    public SqliteConnectionFactory(Path databasePath) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath must not be null")
                .toAbsolutePath()
                .normalize();
    }

    public String jdbcUrl() {
        return "jdbc:sqlite:" + databasePath;
    }

    public Connection openConnection() {
        try {
            Connection connection = DriverManager.getConnection(jdbcUrl());
            try {
                configure(connection);
                return connection;
            } catch (SQLException exception) {
                connection.close();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new LocalDatabaseException(
                    "Não foi possível abrir o banco SQLite local em " + databasePath + ".",
                    exception
            );
        }
    }

    private void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MILLIS);
            statement.execute("PRAGMA journal_mode = WAL");
        }
    }
}
