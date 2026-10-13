-- V7: Sale header and line items for the offline-first sales core.
-- A sale row is only written once payment is confirmed locally; no draft rows are stored here.
-- Monetary amounts are stored as decimal TEXT for exact precision (BigDecimal, scale 2, e.g. "10.50").
-- All identifiers are UUIDs in canonical form (36 characters).
-- Sales are scoped to terminal, tenant, company and branch for full traceability.
CREATE TABLE pdv_sale (
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
                                        ('CASH', 'CARD_DEBIT', 'CARD_CREDIT', 'PIX', 'OTHER')),
    payment_amount       TEXT    NOT NULL,
    change_amount        TEXT    NOT NULL DEFAULT '0.00',
    confirmed_at         TEXT    NOT NULL,
    cancelled_at         TEXT,
    cancellation_reason  TEXT,
    fiscal_document_type TEXT,
    fiscal_number        TEXT,
    created_at           TEXT    NOT NULL
);

CREATE INDEX pdv_sale_terminal_date
    ON pdv_sale (terminal_id, tenant_id, company_id, confirmed_at);

CREATE INDEX pdv_sale_status
    ON pdv_sale (status, terminal_id, tenant_id, company_id);

-- Items capture a product snapshot at confirmation time; the snapshot is immutable.
CREATE TABLE pdv_sale_item (
    sale_id             TEXT    NOT NULL
                                REFERENCES pdv_sale (sale_id) ON DELETE RESTRICT,
    line_number         INTEGER NOT NULL CHECK (line_number >= 1),
    product_id          TEXT    NOT NULL,
    product_name        TEXT    NOT NULL,
    product_sku         TEXT,
    product_unit        TEXT    NOT NULL,
    quantity            TEXT    NOT NULL,
    unit_price_amount   TEXT    NOT NULL,
    discount_amount     TEXT    NOT NULL DEFAULT '0.00',
    total_amount        TEXT    NOT NULL,
    package_id          TEXT,
    package_name        TEXT,
    package_factor      TEXT,
    PRIMARY KEY (sale_id, line_number)
);
