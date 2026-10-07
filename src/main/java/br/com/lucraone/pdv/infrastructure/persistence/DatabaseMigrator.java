package br.com.lucraone.pdv.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

import java.util.Objects;

/**
 * Applies versioned local database migrations. Flyway clean remains disabled.
 */
public final class DatabaseMigrator {

    private final String jdbcUrl;

    public DatabaseMigrator(String jdbcUrl) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
    }

    public void migrate() {
        try {
            Flyway.configure()
                    .dataSource(jdbcUrl, null, null)
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .load()
                    .migrate();
        } catch (FlywayException exception) {
            throw new LocalDatabaseException("Não foi possível migrar o banco SQLite local.", exception);
        }
    }
}
