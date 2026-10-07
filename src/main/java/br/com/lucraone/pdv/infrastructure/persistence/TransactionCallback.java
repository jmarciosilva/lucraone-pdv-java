package br.com.lucraone.pdv.infrastructure.persistence;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface TransactionCallback<T> {

    T execute(Connection connection, TransactionContext transaction) throws SQLException;
}
