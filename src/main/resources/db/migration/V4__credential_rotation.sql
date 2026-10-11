-- Singleton row persisted BEFORE the HTTP rotation call for crash recovery.
-- operation_id is a UUID in canonical form (36 characters).
CREATE TABLE credential_rotation (
    singleton   INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1),
    operation_id TEXT   NOT NULL CHECK (length(operation_id) = 36),
    started_at   TEXT   NOT NULL
);
