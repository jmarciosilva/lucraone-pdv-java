-- V10: Extend the manual MVP payment modalities to six by adding MEAL_VOUCHER and FOOD_VOUCHER
-- (ticket refeição / ticket alimentação), plus the shared voucher declaration EXTERNAL_VOUCHER_APPROVED.
--
-- SQLite cannot alter a CHECK constraint in place, so the two tables that carry the payment_method
-- (and declaration_type) CHECKs are rebuilt by copy. Flyway opens its own connection with foreign
-- keys OFF, so dropping and recreating these mutually referencing tables is safe; existing rows are
-- preserved. Indexes are recreated because they are dropped together with their table. No other table
-- (items, outbox, catalog, credentials) is touched.

-- 1) Rebuild pdv_sale with the extended payment_method CHECK (keeps the V9-added columns/FK).
CREATE TABLE pdv_sale_v10 (
    sale_id              TEXT    NOT NULL PRIMARY KEY CHECK (length(sale_id) = 36),
    terminal_id          TEXT    NOT NULL,
    tenant_id            TEXT    NOT NULL,
    company_id           TEXT    NOT NULL,
    branch_id            TEXT    NOT NULL,
    operator_id          TEXT    NOT NULL,
    operator_name        TEXT    NOT NULL,
    status               TEXT    NOT NULL DEFAULT 'CONFIRMED'
                                         CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    currency             TEXT    NOT NULL CHECK (length(currency) = 3),
    subtotal_amount      TEXT    NOT NULL,
    discount_amount      TEXT    NOT NULL DEFAULT '0.00',
    total_amount         TEXT    NOT NULL,
    payment_method       TEXT    NOT NULL
                                 CHECK (payment_method IN
                                        ('CASH', 'CARD_DEBIT', 'CARD_CREDIT', 'PIX',
                                         'MEAL_VOUCHER', 'FOOD_VOUCHER', 'OTHER')),
    payment_amount       TEXT    NOT NULL,
    change_amount        TEXT    NOT NULL DEFAULT '0.00',
    confirmed_at         TEXT    NOT NULL,
    cancelled_at         TEXT,
    cancellation_reason  TEXT,
    fiscal_document_type TEXT,
    fiscal_number        TEXT,
    created_at           TEXT    NOT NULL,
    payment_confirmation_origin TEXT NOT NULL DEFAULT 'LEGACY_UNCLASSIFIED'
                                 CHECK (payment_confirmation_origin IN ('MANUAL', 'PROVIDER_VERIFIED', 'LEGACY_UNCLASSIFIED')),
    payment_confirmation_id TEXT REFERENCES pdv_manual_payment_attempt (confirmation_id) ON DELETE RESTRICT
);

INSERT INTO pdv_sale_v10 (
    sale_id, terminal_id, tenant_id, company_id, branch_id, operator_id, operator_name,
    status, currency, subtotal_amount, discount_amount, total_amount, payment_method,
    payment_amount, change_amount, confirmed_at, cancelled_at, cancellation_reason,
    fiscal_document_type, fiscal_number, created_at, payment_confirmation_origin, payment_confirmation_id)
SELECT
    sale_id, terminal_id, tenant_id, company_id, branch_id, operator_id, operator_name,
    status, currency, subtotal_amount, discount_amount, total_amount, payment_method,
    payment_amount, change_amount, confirmed_at, cancelled_at, cancellation_reason,
    fiscal_document_type, fiscal_number, created_at, payment_confirmation_origin, payment_confirmation_id
FROM pdv_sale;

DROP TABLE pdv_sale;
ALTER TABLE pdv_sale_v10 RENAME TO pdv_sale;

CREATE INDEX pdv_sale_terminal_date
    ON pdv_sale (terminal_id, tenant_id, company_id, confirmed_at);
CREATE INDEX pdv_sale_status
    ON pdv_sale (status, terminal_id, tenant_id, company_id);
CREATE UNIQUE INDEX pdv_sale_payment_confirmation
    ON pdv_sale (payment_confirmation_id)
    WHERE payment_confirmation_id IS NOT NULL;

-- 2) Rebuild pdv_manual_payment_attempt with extended payment_method and declaration_type CHECKs.
CREATE TABLE pdv_manual_payment_attempt_v10 (
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
                                        CHECK (payment_method IN ('CASH', 'CARD_DEBIT', 'CARD_CREDIT', 'PIX',
                                                                  'MEAL_VOUCHER', 'FOOD_VOUCHER')),
    origin                      TEXT    NOT NULL DEFAULT 'MANUAL' CHECK (origin = 'MANUAL'),
    state                       TEXT    NOT NULL
                                        CHECK (state IN ('PREPARED', 'DECLARED', 'RECONCILIATION_REQUIRED',
                                                         'COMPLETED', 'ABANDONED')),
    payment_amount              TEXT,
    change_amount               TEXT,
    declaration_type            TEXT
                                        CHECK (declaration_type IS NULL OR declaration_type IN
                                               ('CASH_RECEIVED', 'EXTERNAL_CARD_APPROVED',
                                                'PIX_OFFICIAL_CHANNEL_CHECKED', 'EXTERNAL_VOUCHER_APPROVED')),
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
           (payment_method = 'PIX' AND declaration_type = 'PIX_OFFICIAL_CHANNEL_CHECKED') OR
           (payment_method IN ('MEAL_VOUCHER', 'FOOD_VOUCHER') AND declaration_type = 'EXTERNAL_VOUCHER_APPROVED')),
    CHECK (state != 'DECLARED' OR payment_amount IS NOT NULL),
    CHECK ((state = 'COMPLETED' AND linked_sale_id IS NOT NULL AND payment_amount IS NOT NULL)
        OR (state != 'COMPLETED' AND linked_sale_id IS NULL)),
    CHECK (state != 'ABANDONED' OR
           (resolved_by_operator_id IS NOT NULL AND resolved_by_operator_name IS NOT NULL
            AND resolved_at IS NOT NULL AND resolution_reason IS NOT NULL))
);

INSERT INTO pdv_manual_payment_attempt_v10 (
    confirmation_id, sale_id, terminal_id, tenant_id, company_id, branch_id,
    prepared_by_operator_id, prepared_by_operator_name, currency, subtotal_amount,
    discount_amount, total_amount, payment_method, origin, state, payment_amount,
    change_amount, declaration_type, declared_by_operator_id, declared_by_operator_name,
    declared_at, linked_sale_id, resolved_by_operator_id, resolved_by_operator_name,
    resolved_at, resolution_reason, created_at)
SELECT
    confirmation_id, sale_id, terminal_id, tenant_id, company_id, branch_id,
    prepared_by_operator_id, prepared_by_operator_name, currency, subtotal_amount,
    discount_amount, total_amount, payment_method, origin, state, payment_amount,
    change_amount, declaration_type, declared_by_operator_id, declared_by_operator_name,
    declared_at, linked_sale_id, resolved_by_operator_id, resolved_by_operator_name,
    resolved_at, resolution_reason, created_at
FROM pdv_manual_payment_attempt;

DROP TABLE pdv_manual_payment_attempt;
ALTER TABLE pdv_manual_payment_attempt_v10 RENAME TO pdv_manual_payment_attempt;

CREATE INDEX pdv_manual_payment_attempt_recovery
    ON pdv_manual_payment_attempt (terminal_id, state, created_at);
