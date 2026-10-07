package br.com.lucraone.pdv.infrastructure.persistence;

/**
 * Indicates that the local database could not be prepared or accessed safely.
 */
public final class LocalDatabaseException extends RuntimeException {

    public LocalDatabaseException(String message) {
        super(message);
    }

    public LocalDatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
