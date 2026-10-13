-- V8: Outbox for deferred synchronisation of confirmed/cancelled sales with the Laravel backend.
-- One row is inserted atomically with the sale in the same SQLite transaction (Fase 2B will read it).
-- The sync processor reads PENDING rows ordered by next_attempt_at ASC NULLS FIRST and transmits
-- each to the backend, then marks them SENT or increments failure_count on transient errors.
CREATE TABLE pdv_sale_outbox (
    outbox_id           TEXT    NOT NULL PRIMARY KEY CHECK (length(outbox_id) = 36),
    sale_id             TEXT    NOT NULL
                                REFERENCES pdv_sale (sale_id) ON DELETE RESTRICT,
    event_type          TEXT    NOT NULL
                                CHECK (event_type IN ('SALE_CONFIRMED', 'SALE_CANCELLED')),
    payload_version     INTEGER NOT NULL DEFAULT 1,
    status              TEXT    NOT NULL DEFAULT 'PENDING'
                                CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    created_at          TEXT    NOT NULL,
    sent_at             TEXT,
    failure_count       INTEGER NOT NULL DEFAULT 0,
    next_attempt_at     TEXT,
    last_failure_reason TEXT
);

-- Partial index: only unprocessed rows are scanned by the sync processor.
CREATE INDEX pdv_sale_outbox_pending
    ON pdv_sale_outbox (next_attempt_at, created_at)
    WHERE status = 'PENDING';
