package br.com.lucraone.pdv.infrastructure.persistence;

import br.com.lucraone.pdv.application.catalog.*;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Durable delivery staging and atomic version activation in the terminal SQLite database. */
public final class JdbcCatalogDeliveryRepository implements CatalogDeliveryRepository {
    private final JdbcTransactionManager transactions;

    public JdbcCatalogDeliveryRepository(JdbcTransactionManager transactions) {
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override public void begin(CatalogCacheContext context, CatalogDeliveryManifest manifest) {
        transactions.inTransaction((c, tx) -> {
            try (PreparedStatement q = c.prepareStatement("SELECT publication_id,version,schema_version,checksum,product_count,price_count,package_count,part_size,part_count FROM pdv_catalog_delivery_state WHERE terminal_id=? AND tenant_id=? AND company_id=? AND delivery_id=?")) {
                bindContext(q, context, 1); q.setString(4, manifest.deliveryId());
                try (ResultSet r = q.executeQuery()) {
                    if (r.next()) {
                        if (!r.getString(1).equals(manifest.publicationId()) || r.getLong(2) != manifest.version()
                                || r.getInt(3) != manifest.schemaVersion() || !r.getString(4).equalsIgnoreCase(manifest.checksum())
                                || r.getInt(5) != manifest.productCount() || r.getInt(6) != manifest.priceCount()
                                || r.getInt(7) != manifest.packageCount() || r.getInt(8) != manifest.partSize()
                                || r.getInt(9) != manifest.partCount()) throw new IllegalStateException("Delivery manifest changed after staging began.");
                        return null;
                    }
                }
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO pdv_catalog_delivery_state(terminal_id,tenant_id,company_id,delivery_id,publication_id,version,schema_version,checksum,product_count,price_count,package_count,part_size,part_count,resume_from_part,last_part,state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                bindContext(s, context, 1); s.setString(4, manifest.deliveryId()); s.setString(5, manifest.publicationId());
                s.setLong(6, manifest.version()); s.setInt(7, manifest.schemaVersion()); s.setString(8, manifest.checksum());
                s.setInt(9, manifest.productCount()); s.setInt(10, manifest.priceCount()); s.setInt(11, manifest.packageCount());
                s.setInt(12, manifest.partSize()); s.setInt(13, manifest.partCount()); s.setInt(14, manifest.resumeFromPart());
                s.setInt(15, -1); s.setString(16, "DOWNLOADING"); s.executeUpdate();
            }
            return null;
        });
    }

    @Override public int lastStoredPart(CatalogCacheContext context, String deliveryId) {
        return transactions.inTransaction((c, tx) -> {
            try (PreparedStatement s = c.prepareStatement("SELECT last_part FROM pdv_catalog_delivery_state WHERE terminal_id=? AND tenant_id=? AND company_id=? AND delivery_id=?")) {
                bindContext(s, context, 1); s.setString(4, deliveryId);
                try (ResultSet r = s.executeQuery()) { return r.next() ? r.getInt(1) : -1; }
            }
        });
    }

    @Override public void storePart(CatalogCacheContext context, String deliveryId, CatalogDeliveryPart part) {
        transactions.inTransaction((c, tx) -> {
            int current;
            try (PreparedStatement s = c.prepareStatement("SELECT last_part FROM pdv_catalog_delivery_state WHERE terminal_id=? AND tenant_id=? AND company_id=? AND delivery_id=?")) {
                bindContext(s, context, 1); s.setString(4, deliveryId);
                try (ResultSet r = s.executeQuery()) { if (!r.next()) throw new IllegalStateException("Delivery staging record is missing."); current = r.getInt(1); }
            }
            if (part.part() > current + 1) throw new IllegalStateException("Catalog parts must be stored sequentially.");
            try (PreparedStatement s = c.prepareStatement("INSERT INTO pdv_catalog_delivery_part(terminal_id,tenant_id,company_id,delivery_id,part_number,checksum,content) VALUES(?,?,?,?,?,?,?) ON CONFLICT(terminal_id,tenant_id,company_id,delivery_id,part_number) DO UPDATE SET checksum=excluded.checksum,content=excluded.content")) {
                bindContext(s, context, 1); s.setString(4, deliveryId); s.setInt(5, part.part()); s.setString(6, part.checksum()); s.setBytes(7, part.content()); s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE pdv_catalog_delivery_state SET last_part=MAX(last_part,?) WHERE terminal_id=? AND tenant_id=? AND company_id=? AND delivery_id=?")) {
                s.setInt(1, part.part()); bindContext(s, context, 2); s.setString(5, deliveryId); s.executeUpdate();
            }
            return null;
        });
    }

    @Override public List<byte[]> parts(CatalogCacheContext context, String deliveryId) {
        return transactions.inTransaction((c, tx) -> {
            List<byte[]> result = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement("SELECT content FROM pdv_catalog_delivery_part WHERE terminal_id=? AND tenant_id=? AND company_id=? AND delivery_id=? ORDER BY part_number")) {
                bindContext(s, context, 1); s.setString(4, deliveryId);
                try (ResultSet r = s.executeQuery()) { while (r.next()) result.add(r.getBytes(1)); }
            }
            return List.copyOf(result);
        });
    }

    @Override public void markReceived(CatalogCacheContext context, String deliveryId) { updateState(context, deliveryId, "RECEIVED"); }
    @Override public void markConfirmed(CatalogCacheContext context, String deliveryId) { updateState(context, deliveryId, "CONFIRMED"); }

    @Override public void markApplied(CatalogCacheContext context, CatalogDeliveryManifest manifest,
            List<CatalogProduct> products, Instant appliedAt) {
        List<CatalogProduct> safeProducts = List.copyOf(products);
        transactions.inTransaction((c, tx) -> {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO pdv_catalog_snapshot_version(terminal_id,tenant_id,company_id,version,delivery_id,checksum,product_count,price_count,package_count,applied_at) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(terminal_id,tenant_id,company_id,version) DO NOTHING")) {
                bindContext(s, context, 1); s.setLong(4, manifest.version()); s.setString(5, manifest.deliveryId()); s.setString(6, manifest.checksum());
                s.setInt(7, manifest.productCount()); s.setInt(8, manifest.priceCount()); s.setInt(9, manifest.packageCount()); s.setString(10, appliedAt.toString()); s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("SELECT delivery_id,checksum FROM pdv_catalog_snapshot_version WHERE terminal_id=? AND tenant_id=? AND company_id=? AND version=?")) {
                bindContext(s, context, 1); s.setLong(4, manifest.version());
                try (ResultSet r=s.executeQuery()) { if (!r.next() || !manifest.deliveryId().equals(r.getString(1)) || !manifest.checksum().equalsIgnoreCase(r.getString(2))) throw new IllegalStateException("Catalog version conflicts with an already applied publication."); }
            }
            try (PreparedStatement s = c.prepareStatement("DELETE FROM pdv_catalog_snapshot_product WHERE terminal_id=? AND tenant_id=? AND company_id=? AND version=?")) {
                bindContext(s, context, 1); s.setLong(4, manifest.version()); s.executeUpdate();
            }
            try (PreparedStatement product = c.prepareStatement("INSERT INTO pdv_catalog_snapshot_product VALUES(?,?,?,?,?,?,?,?,?,?,?)");
                 PreparedStatement price = c.prepareStatement("INSERT INTO pdv_catalog_snapshot_price VALUES(?,?,?,?,?,?,?)");
                 PreparedStatement pack = c.prepareStatement("INSERT INTO pdv_catalog_snapshot_package VALUES(?,?,?,?,?,?,?,?,?)")) {
                for (CatalogProduct p : safeProducts) {
                    bindContext(product, context, 1); product.setLong(4, manifest.version()); product.setString(5, p.id());
                    product.setString(6, p.sku()); product.setString(7, p.barcode()); product.setString(8, p.name()); product.setString(9, p.unit()); product.setString(10, p.status());
                    if (p.updatedAt().isPresent()) product.setString(11, p.updatedAt().orElseThrow().toString()); else product.setNull(11, java.sql.Types.VARCHAR);
                    product.addBatch();
                    for (CatalogPrice v : p.salePrices()) { bindContext(price, context, 1); price.setLong(4, manifest.version()); price.setString(5, p.id()); price.setString(6, v.currency()); price.setString(7, v.amount().toPlainString()); price.addBatch(); }
                    for (CatalogPackage v : p.packages()) { bindContext(pack, context, 1); pack.setLong(4, manifest.version()); pack.setString(5, p.id()); pack.setString(6, v.id()); pack.setString(7, v.name()); pack.setString(8, v.barcode()); pack.setString(9, v.factor().toPlainString()); pack.addBatch(); }
                }
                product.executeBatch(); price.executeBatch(); pack.executeBatch();
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO pdv_catalog_snapshot_active(terminal_id,tenant_id,company_id,version,activated_at) VALUES(?,?,?,?,?) ON CONFLICT(terminal_id,tenant_id,company_id) DO UPDATE SET version=excluded.version,activated_at=excluded.activated_at")) {
                bindContext(s, context, 1); s.setLong(4, manifest.version()); s.setString(5, appliedAt.toString()); s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE pdv_catalog_delivery_state SET state='APPLIED' WHERE terminal_id=? AND tenant_id=? AND company_id=? AND delivery_id=?")) {
                bindContext(s, context, 1); s.setString(4, manifest.deliveryId()); s.executeUpdate();
            }
            return null;
        });
    }

    @Override public List<RecoverableCatalogDelivery> recoverable(CatalogCacheContext context) {
        return transactions.inTransaction((c, tx) -> {
            List<RecoverableCatalogDelivery> result = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement("SELECT delivery_id,publication_id,version,schema_version,checksum,product_count,price_count,package_count,part_size,part_count,resume_from_part,state FROM pdv_catalog_delivery_state WHERE terminal_id=? AND tenant_id=? AND company_id=? AND state IN ('RECEIVED','APPLIED') ORDER BY version")) {
                bindContext(s, context, 1); try (ResultSet r=s.executeQuery()) { while (r.next()) {
                    CatalogDeliveryManifest m = new CatalogDeliveryManifest(r.getString(1),r.getString(2),r.getLong(3),r.getInt(4),"sha256",r.getString(5),r.getInt(6),r.getInt(7),r.getInt(8),r.getInt(9),r.getInt(10),r.getInt(11));
                    result.add(new RecoverableCatalogDelivery(m,r.getString(12)));
                }}
            }
            return List.copyOf(result);
        });
    }

    private void updateState(CatalogCacheContext context, String deliveryId, String state) {
        transactions.inTransaction((c, tx) -> { try (PreparedStatement s=c.prepareStatement("UPDATE pdv_catalog_delivery_state SET state=? WHERE terminal_id=? AND tenant_id=? AND company_id=? AND delivery_id=?")) { s.setString(1,state); bindContext(s,context,2); s.setString(5,deliveryId); if(s.executeUpdate()!=1) throw new IllegalStateException("Delivery staging record is missing."); } return null; });
    }
    private static void bindContext(PreparedStatement s, CatalogCacheContext c, int start) throws java.sql.SQLException {
        s.setString(start,c.terminalId()); s.setString(start+1,c.tenantId()); s.setString(start+2,c.companyId());
    }
}
