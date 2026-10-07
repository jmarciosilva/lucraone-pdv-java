package br.com.lucraone.pdv.infrastructure.persistence;

/**
 * Lets a transaction callback explicitly request rollback without using exceptions for control flow.
 */
public final class TransactionContext {

    private boolean rollbackOnly;

    public void markRollbackOnly() {
        rollbackOnly = true;
    }

    boolean isRollbackOnly() {
        return rollbackOnly;
    }
}
