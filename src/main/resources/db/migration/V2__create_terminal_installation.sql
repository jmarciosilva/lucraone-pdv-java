-- Local identity of this installation. A single row is allowed; installation_id is generated once on the terminal.
CREATE TABLE installation (
    singleton       INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1),
    installation_id TEXT    NOT NULL UNIQUE CHECK (length(installation_id) = 36),
    created_at      TEXT    NOT NULL
);

-- Backend references of a provisioned terminal. No row means the terminal is not provisioned.
-- terminal_id and branch_id are opaque references and must be stored together.
CREATE TABLE terminal_provisioning (
    singleton       INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1),
    installation_id TEXT    NOT NULL REFERENCES installation (installation_id),
    terminal_id     TEXT    NOT NULL CHECK (length(trim(terminal_id)) > 0),
    branch_id       TEXT    NOT NULL CHECK (length(trim(branch_id)) > 0),
    provisioned_at  TEXT    NOT NULL
);
