package br.com.lucraone.pdv.infrastructure.persistence;

import java.sql.Connection;
import java.util.Objects;

/**
 * Prepares the terminal-local SQLite database before application features use it.
 */
public final class LocalDatabaseInitializer {

    private final LocalDataDirectory localDataDirectory;

    public LocalDatabaseInitializer() {
        this(new LocalDataDirectory());
    }

    public LocalDatabaseInitializer(LocalDataDirectory localDataDirectory) {
        this.localDataDirectory = Objects.requireNonNull(localDataDirectory, "localDataDirectory must not be null");
    }

    public SqliteConnectionFactory initialize() {
        localDataDirectory.prepareOperationalDirectories();

        SqliteConnectionFactory connections = new SqliteConnectionFactory(localDataDirectory.databasePath());
        try (Connection ignored = connections.openConnection()) {
            // Creates and configures a new SQLite file before Flyway accesses it.
        } catch (Exception exception) {
            if (exception instanceof LocalDatabaseException localDatabaseException) {
                throw localDatabaseException;
            }
            throw new LocalDatabaseException("Não foi possível preparar a conexão SQLite local.", exception);
        }

        new DatabaseMigrator(connections.jdbcUrl()).migrate();
        return connections;
    }
}
