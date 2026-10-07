package br.com.lucraone.pdv.infrastructure.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Runs a callback in one JDBC transaction using a short-lived SQLite connection.
 */
public final class JdbcTransactionManager {

    private final SqliteConnectionFactory connectionFactory;

    public JdbcTransactionManager(SqliteConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory must not be null");
    }

    public <T> T inTransaction(TransactionCallback<T> callback) {
        Objects.requireNonNull(callback, "callback must not be null");

        // The connection is discarded after the transaction, so autoCommit is not restored:
        // the SQLite driver commits when autoCommit is switched back to true.
        try (Connection connection = connectionFactory.openConnection()) {
            connection.setAutoCommit(false);
            try {
                TransactionContext transaction = new TransactionContext();
                T result = callback.execute(connection, transaction);
                if (transaction.isRollbackOnly()) {
                    connection.rollback();
                } else {
                    connection.commit();
                }
                return result;
            } catch (SQLException exception) {
                rollbackAfterFailure(connection, exception);
                throw new LocalDatabaseException("A transação SQLite local falhou e foi revertida.", exception);
            } catch (RuntimeException exception) {
                rollbackAfterFailure(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new LocalDatabaseException("Não foi possível concluir a transação SQLite local.", exception);
        }
    }

    private void rollbackAfterFailure(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
