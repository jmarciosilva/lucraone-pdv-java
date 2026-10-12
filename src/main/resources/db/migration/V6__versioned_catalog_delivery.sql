-- Versioned snapshots are isolated from V5's temporary online query cache.
CREATE TABLE pdv_catalog_delivery_state (
    terminal_id TEXT NOT NULL, tenant_id TEXT NOT NULL, company_id TEXT NOT NULL,
    delivery_id TEXT NOT NULL, publication_id TEXT NOT NULL, version INTEGER NOT NULL,
    schema_version INTEGER NOT NULL, checksum TEXT NOT NULL,
    product_count INTEGER NOT NULL, price_count INTEGER NOT NULL, package_count INTEGER NOT NULL,
    part_size INTEGER NOT NULL, part_count INTEGER NOT NULL, resume_from_part INTEGER NOT NULL,
    last_part INTEGER NOT NULL DEFAULT -1, state TEXT NOT NULL,
    PRIMARY KEY (terminal_id, tenant_id, company_id, delivery_id)
);
CREATE TABLE pdv_catalog_delivery_part (
    terminal_id TEXT NOT NULL, tenant_id TEXT NOT NULL, company_id TEXT NOT NULL,
    delivery_id TEXT NOT NULL, part_number INTEGER NOT NULL, checksum TEXT NOT NULL,
    content BLOB NOT NULL,
    PRIMARY KEY (terminal_id, tenant_id, company_id, delivery_id, part_number),
    FOREIGN KEY (terminal_id, tenant_id, company_id, delivery_id)
      REFERENCES pdv_catalog_delivery_state (terminal_id, tenant_id, company_id, delivery_id) ON DELETE CASCADE
);
CREATE TABLE pdv_catalog_snapshot_version (
    terminal_id TEXT NOT NULL, tenant_id TEXT NOT NULL, company_id TEXT NOT NULL,
    version INTEGER NOT NULL, delivery_id TEXT NOT NULL, checksum TEXT NOT NULL,
    product_count INTEGER NOT NULL, price_count INTEGER NOT NULL, package_count INTEGER NOT NULL,
    applied_at TEXT NOT NULL,
    PRIMARY KEY (terminal_id, tenant_id, company_id, version),
    UNIQUE (terminal_id, tenant_id, company_id, delivery_id)
);
CREATE TABLE pdv_catalog_snapshot_active (
    terminal_id TEXT NOT NULL, tenant_id TEXT NOT NULL, company_id TEXT NOT NULL,
    version INTEGER NOT NULL, activated_at TEXT NOT NULL,
    PRIMARY KEY (terminal_id, tenant_id, company_id),
    FOREIGN KEY (terminal_id, tenant_id, company_id, version)
      REFERENCES pdv_catalog_snapshot_version (terminal_id, tenant_id, company_id, version)
);
CREATE TABLE pdv_catalog_snapshot_product (
    terminal_id TEXT NOT NULL, tenant_id TEXT NOT NULL, company_id TEXT NOT NULL, version INTEGER NOT NULL,
    product_id TEXT NOT NULL, sku TEXT, barcode TEXT, name TEXT NOT NULL, unit TEXT NOT NULL,
    status TEXT NOT NULL, updated_at TEXT,
    PRIMARY KEY (terminal_id, tenant_id, company_id, version, product_id),
    FOREIGN KEY (terminal_id, tenant_id, company_id, version)
      REFERENCES pdv_catalog_snapshot_version (terminal_id, tenant_id, company_id, version) ON DELETE CASCADE
);
CREATE TABLE pdv_catalog_snapshot_price (
    terminal_id TEXT NOT NULL, tenant_id TEXT NOT NULL, company_id TEXT NOT NULL, version INTEGER NOT NULL,
    product_id TEXT NOT NULL, currency TEXT NOT NULL, amount TEXT NOT NULL,
    PRIMARY KEY (terminal_id, tenant_id, company_id, version, product_id, currency),
    FOREIGN KEY (terminal_id, tenant_id, company_id, version, product_id)
      REFERENCES pdv_catalog_snapshot_product (terminal_id, tenant_id, company_id, version, product_id) ON DELETE CASCADE
);
CREATE TABLE pdv_catalog_snapshot_package (
    terminal_id TEXT NOT NULL, tenant_id TEXT NOT NULL, company_id TEXT NOT NULL, version INTEGER NOT NULL,
    product_id TEXT NOT NULL, package_id TEXT NOT NULL, name TEXT NOT NULL, barcode TEXT, factor TEXT NOT NULL,
    PRIMARY KEY (terminal_id, tenant_id, company_id, version, product_id, package_id),
    FOREIGN KEY (terminal_id, tenant_id, company_id, version, product_id)
      REFERENCES pdv_catalog_snapshot_product (terminal_id, tenant_id, company_id, version, product_id) ON DELETE CASCADE
);
CREATE INDEX pdv_catalog_snapshot_product_lookup
    ON pdv_catalog_snapshot_product (terminal_id, tenant_id, company_id, version, name, sku, barcode);
