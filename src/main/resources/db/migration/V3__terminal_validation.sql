-- Non-secret evidence only. A random generation binds evidence to the protected credential envelope.
CREATE TABLE terminal_validation (
    singleton INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1),
    credential_generation TEXT NOT NULL CHECK (length(credential_generation) = 36),
    installation_id TEXT NOT NULL REFERENCES installation (installation_id),
    terminal_id TEXT NOT NULL CHECK (length(trim(terminal_id)) BETWEEN 1 AND 64),
    branch_id TEXT NOT NULL CHECK (length(trim(branch_id)) BETWEEN 1 AND 64),
    backend_identity TEXT NOT NULL CHECK (length(backend_identity) > 0),
    validated_at TEXT NOT NULL
);
