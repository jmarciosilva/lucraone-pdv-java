-- V9: Durable manual-payment workflow and payment confirmation provenance.
-- Existing V7 sales are deliberately classified as LEGACY_UNCLASSIFIED.
CREATE TABLE pdv_manual_payment_attempt (
    confirmation_id             TEXT    NOT NULL PRIMARY KEY CHECK (length(confirmation_id) = 36),
    sale_id                     TEXT    NOT NULL UNIQUE CHECK (length(sale_id) = 36),
    terminal_id                 TEXT    NOT NULL,
    tenant_id                   TEXT    NOT NULL,
    company_id                  TEXT    NOT NULL,
    branch_id                   TEXT    NOT NULL,
    prepared_by_operator_id     TEXT    NOT NULL,
    prepared_by_operator_name   TEXT    NOT NULL,
    currency                    TEXT    NOT NULL CHECK (length(currency) = 3),
    subtotal_amount             TEXT    NOT NULL,
    discount_amount             TEXT    NOT NULL,
    total_amount                TEXT    NOT NULL,
    payment_method              TEXT    NOT NULL
                                        CHECK (payment_method IN ('CASH', 'CARD_DEBIT', 'CARD_CREDIT', 'PIX')),
    origin                      TEXT    NOT NULL DEFAULT 'MANUAL' CHECK (origin = 'MANUAL'),
    state                       TEXT    NOT NULL
                                        CHECK (state IN ('PREPARED', 'DECLARED', 'RECONCILIATION_REQUIRED',
                                                         'COMPLETED', 'ABANDONED')),
    payment_amount              TEXT,
    change_amount               TEXT,
    declaration_type            TEXT
                                        CHECK (declaration_type IS NULL OR declaration_type IN
                                               ('CASH_RECEIVED', 'EXTERNAL_CARD_APPROVED',
                                                'PIX_OFFICIAL_CHANNEL_CHECKED')),
    declared_by_operator_id     TEXT,
    declared_by_operator_name   TEXT,
    declared_at                 TEXT,
    linked_sale_id              TEXT UNIQUE REFERENCES pdv_sale (sale_id) ON DELETE RESTRICT,
    resolved_by_operator_id    TEXT,
    resolved_by_operator_name  TEXT,
    resolved_at                 TEXT,
    resolution_reason           TEXT,
    created_at                  TEXT    NOT NULL,
    CHECK ((payment_amount IS NULL AND change_amount IS NULL AND declaration_type IS NULL
            AND declared_by_operator_id IS NULL AND declared_by_operator_name IS NULL AND declared_at IS NULL)
        OR (payment_amount IS NOT NULL AND change_amount IS NOT NULL AND declaration_type IS NOT NULL
            AND declared_by_operator_id IS NOT NULL AND declared_by_operator_name IS NOT NULL AND declared_at IS NOT NULL)),
    CHECK (declaration_type IS NULL OR
           (payment_method = 'CASH' AND declaration_type = 'CASH_RECEIVED') OR
           (payment_method IN ('CARD_DEBIT', 'CARD_CREDIT') AND declaration_type = 'EXTERNAL_CARD_APPROVED') OR
           (payment_method = 'PIX' AND declaration_type = 'PIX_OFFICIAL_CHANNEL_CHECKED')),
    CHECK (state != 'DECLARED' OR payment_amount IS NOT NULL),
    CHECK ((state = 'COMPLETED' AND linked_sale_id IS NOT NULL AND payment_amount IS NOT NULL)
        OR (state != 'COMPLETED' AND linked_sale_id IS NULL)),
    CHECK (state != 'ABANDONED' OR
           (resolved_by_operator_id IS NOT NULL AND resolved_by_operator_name IS NOT NULL
            AND resolved_at IS NOT NULL AND resolution_reason IS NOT NULL))
);

CREATE INDEX pdv_manual_payment_attempt_recovery
    ON pdv_manual_payment_attempt (terminal_id, state, created_at);

CREATE TABLE pdv_manual_payment_attempt_item (
    confirmation_id     TEXT    NOT NULL
                                REFERENCES pdv_manual_payment_attempt (confirmation_id) ON DELETE RESTRICT,
    line_number         INTEGER NOT NULL CHECK (line_number >= 1),
    product_id          TEXT    NOT NULL,
    product_name        TEXT    NOT NULL,
    product_sku         TEXT,
    product_unit        TEXT    NOT NULL,
    quantity            TEXT    NOT NULL,
    unit_price_amount   TEXT    NOT NULL,
    discount_amount     TEXT    NOT NULL,
    total_amount        TEXT    NOT NULL,
    package_id          TEXT,
    package_name        TEXT,
    package_factor      TEXT,
    PRIMARY KEY (confirmation_id, line_number)
);

ALTER TABLE pdv_sale ADD COLUMN payment_confirmation_origin TEXT NOT NULL DEFAULT 'LEGACY_UNCLASSIFIED'
    CHECK (payment_confirmation_origin IN ('MANUAL', 'PROVIDER_VERIFIED', 'LEGACY_UNCLASSIFIED'));

ALTER TABLE pdv_sale ADD COLUMN payment_confirmation_id TEXT
    REFERENCES pdv_manual_payment_attempt (confirmation_id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX pdv_sale_payment_confirmation
    ON pdv_sale (payment_confirmation_id)
    WHERE payment_confirmation_id IS NOT NULL;
