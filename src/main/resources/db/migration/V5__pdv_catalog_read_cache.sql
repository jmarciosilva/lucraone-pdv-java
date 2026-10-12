-- Read cache only. Rows are scoped to the exact terminal/tenant/company context.
CREATE TABLE pdv_catalog_product (
    terminal_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    company_id TEXT NOT NULL,
    product_id TEXT NOT NULL,
    sku TEXT,
    barcode TEXT,
    name TEXT NOT NULL,
    unit TEXT NOT NULL,
    status TEXT NOT NULL,
    updated_at TEXT,
    queried_at TEXT NOT NULL,
    PRIMARY KEY (terminal_id, tenant_id, company_id, product_id)
);
CREATE INDEX pdv_catalog_product_lookup
    ON pdv_catalog_product (terminal_id, tenant_id, company_id, name, sku, barcode);
CREATE TABLE pdv_catalog_sale_price (
    terminal_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    company_id TEXT NOT NULL,
    product_id TEXT NOT NULL,
    currency TEXT NOT NULL,
    amount TEXT NOT NULL,
    PRIMARY KEY (terminal_id, tenant_id, company_id, product_id, currency),
    FOREIGN KEY (terminal_id, tenant_id, company_id, product_id)
      REFERENCES pdv_catalog_product (terminal_id, tenant_id, company_id, product_id) ON DELETE CASCADE
);
CREATE TABLE pdv_catalog_package (
    terminal_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    company_id TEXT NOT NULL,
    product_id TEXT NOT NULL,
    package_id TEXT NOT NULL,
    name TEXT NOT NULL,
    barcode TEXT,
    factor TEXT NOT NULL,
    PRIMARY KEY (terminal_id, tenant_id, company_id, product_id, package_id),
    FOREIGN KEY (terminal_id, tenant_id, company_id, product_id)
      REFERENCES pdv_catalog_product (terminal_id, tenant_id, company_id, product_id) ON DELETE CASCADE
);
