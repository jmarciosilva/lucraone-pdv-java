package br.com.lucraone.pdv.infrastructure.persistence;

import br.com.lucraone.pdv.application.catalog.*;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class JdbcCatalogCacheRepository implements CatalogCacheRepository {
    private final JdbcTransactionManager transactions;

    public JdbcCatalogCacheRepository(JdbcTransactionManager transactions) {
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public void upsert(CatalogCacheContext context, List<CatalogProduct> products, Instant queriedAt) {
        Objects.requireNonNull(context); List<CatalogProduct> safeProducts = List.copyOf(products); Objects.requireNonNull(queriedAt);
        transactions.inTransaction((connection, tx) -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO pdv_catalog_product(terminal_id,tenant_id,company_id,product_id,sku,barcode,name,unit,status,updated_at,queried_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(terminal_id,tenant_id,company_id,product_id) DO UPDATE SET
                    sku=excluded.sku,barcode=excluded.barcode,name=excluded.name,unit=excluded.unit,status=excluded.status,
                    updated_at=excluded.updated_at,queried_at=excluded.queried_at
                    """)) {
                for (CatalogProduct product : safeProducts) {
                    bindContext(statement, context, 1); statement.setString(4, product.id());
                    statement.setString(5, product.sku()); statement.setString(6, product.barcode());
                    statement.setString(7, product.name()); statement.setString(8, product.unit());
                    statement.setString(9, product.status());
                    if (product.updatedAt().isPresent()) statement.setString(10, product.updatedAt().orElseThrow().toString());
                    else statement.setNull(10, java.sql.Types.VARCHAR);
                    statement.setString(11, queriedAt.toString()); statement.addBatch();
                }
                statement.executeBatch();
            }
            try (PreparedStatement deletePrices = connection.prepareStatement(
                    "DELETE FROM pdv_catalog_sale_price WHERE terminal_id=? AND tenant_id=? AND company_id=? AND product_id=?");
                 PreparedStatement deletePackages = connection.prepareStatement(
                    "DELETE FROM pdv_catalog_package WHERE terminal_id=? AND tenant_id=? AND company_id=? AND product_id=?");
                 PreparedStatement insertPrice = connection.prepareStatement(
                    "INSERT INTO pdv_catalog_sale_price VALUES(?,?,?,?,?,?)");
                 PreparedStatement insertPackage = connection.prepareStatement(
                    "INSERT INTO pdv_catalog_package VALUES(?,?,?,?,?,?,?,?)")) {
                for (CatalogProduct product : safeProducts) {
                    bindProduct(deletePrices, context, product.id()); deletePrices.executeUpdate();
                    bindProduct(deletePackages, context, product.id()); deletePackages.executeUpdate();
                    for (CatalogPrice price : product.salePrices()) {
                        bindContext(insertPrice, context, 1); insertPrice.setString(4, product.id());
                        insertPrice.setString(5, price.currency()); insertPrice.setString(6, price.amount().toPlainString()); insertPrice.addBatch();
                    }
                    for (CatalogPackage pack : product.packages()) {
                        bindContext(insertPackage, context, 1); insertPackage.setString(4, product.id());
                        insertPackage.setString(5, pack.id()); insertPackage.setString(6, pack.name());
                        insertPackage.setString(7, pack.barcode()); insertPackage.setString(8, pack.factor().toPlainString()); insertPackage.addBatch();
                    }
                }
                insertPrice.executeBatch(); insertPackage.executeBatch();
            }
            return null;
        });
    }

    @Override
    public List<CatalogCacheRecord> findAll(CatalogCacheContext context) {
        Objects.requireNonNull(context);
        return transactions.inTransaction((connection, tx) -> {
            List<CatalogCacheRecord> records = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT product_id,sku,barcode,name,unit,status,updated_at,queried_at FROM pdv_catalog_product
                    WHERE terminal_id=? AND tenant_id=? AND company_id=? ORDER BY name COLLATE NOCASE,product_id
                    """)) {
                bindContext(query, context, 1);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        String id = rows.getString("product_id");
                        records.add(new CatalogCacheRecord(new CatalogProduct(id, rows.getString("sku"), rows.getString("barcode"),
                                rows.getString("name"), rows.getString("unit"), rows.getString("status"),
                                readPrices(connection, context, id), readPackages(connection, context, id),
                                Optional.ofNullable(rows.getString("updated_at")).map(Instant::parse)), Instant.parse(rows.getString("queried_at"))));
                    }
                }
            }
            return List.copyOf(records);
        });
    }

    private List<CatalogPrice> readPrices(java.sql.Connection c, CatalogCacheContext x, String id) throws java.sql.SQLException {
        List<CatalogPrice> values = new ArrayList<>();
        try (PreparedStatement s=c.prepareStatement("SELECT amount,currency FROM pdv_catalog_sale_price WHERE terminal_id=? AND tenant_id=? AND company_id=? AND product_id=? ORDER BY currency")) {
            bindProduct(s,x,id); try(ResultSet r=s.executeQuery()){ while(r.next()) values.add(new CatalogPrice(new java.math.BigDecimal(r.getString(1)),r.getString(2))); }
        } return List.copyOf(values);
    }

    private List<CatalogPackage> readPackages(java.sql.Connection c, CatalogCacheContext x, String id) throws java.sql.SQLException {
        List<CatalogPackage> values = new ArrayList<>();
        try (PreparedStatement s=c.prepareStatement("SELECT package_id,name,barcode,factor FROM pdv_catalog_package WHERE terminal_id=? AND tenant_id=? AND company_id=? AND product_id=? ORDER BY package_id")) {
            bindProduct(s,x,id); try(ResultSet r=s.executeQuery()){ while(r.next()) values.add(new CatalogPackage(r.getString(1),r.getString(2),r.getString(3),new java.math.BigDecimal(r.getString(4)))); }
        } return List.copyOf(values);
    }

    private static void bindContext(PreparedStatement s, CatalogCacheContext c, int start) throws java.sql.SQLException {
        s.setString(start,c.terminalId()); s.setString(start+1,c.tenantId()); s.setString(start+2,c.companyId());
    }
    private static void bindProduct(PreparedStatement s, CatalogCacheContext c, String id) throws java.sql.SQLException {
        bindContext(s,c,1); s.setString(4,id);
    }
}
