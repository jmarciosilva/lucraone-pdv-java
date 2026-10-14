package br.com.lucraone.pdv.infrastructure.persistence;

import br.com.lucraone.pdv.application.api.PdvApiException;
import br.com.lucraone.pdv.application.api.PdvApiFailure;
import br.com.lucraone.pdv.application.api.PdvFailureKind;
import br.com.lucraone.pdv.application.catalog.*;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** Read-only queries scoped to the version pointer atomically activated by catalog distribution. */
public final class JdbcActiveCatalogRepository implements ActiveCatalogRepository {
    private final JdbcTransactionManager transactions;
    public JdbcActiveCatalogRepository(JdbcTransactionManager transactions) { this.transactions = Objects.requireNonNull(transactions); }

    @Override public Optional<CatalogProduct> findById(CatalogCacheContext context, String productId) {
        Objects.requireNonNull(context); Objects.requireNonNull(productId);
        if (productId.isBlank()) throw new IllegalArgumentException("productId must not be blank");
        return transactions.inTransaction((c, tx) -> {
            Optional<Long> version = activeVersion(c, context);
            if (version.isEmpty()) return Optional.empty();
            try (PreparedStatement s = c.prepareStatement("""
                    SELECT product_id, sku, barcode, name, unit, status, updated_at
                    FROM pdv_catalog_snapshot_product
                    WHERE terminal_id=? AND tenant_id=? AND company_id=? AND version=? AND product_id=?
                    """)) {
                bindContext(s, context, 1);
                s.setLong(4, version.orElseThrow());
                s.setString(5, productId);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) return Optional.empty();
                    ProductRow row = productRow(r);
                    return Optional.of(loadProducts(c, context, version.orElseThrow(), List.of(row)).getFirst());
                }
            }
        });
    }

    @Override public CatalogPage search(CatalogCacheContext context, String search, int perPage, String cursor) {
        Objects.requireNonNull(context); Objects.requireNonNull(search);
        if (perPage < 1 || perPage > 100) throw new IllegalArgumentException("perPage must be between 1 and 100");
        int offset = parseCursor(cursor);
        return transactions.inTransaction((c, tx) -> {
            Optional<Long> version = activeVersion(c, context);
            if (version.isEmpty()) return new CatalogPage(List.of(), perPage, null, false);
            String term = "%" + escapeLike(search.strip().toLowerCase(Locale.ROOT)) + "%";
            List<ProductRow> rows = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement("""
                    SELECT p.product_id,p.sku,p.barcode,p.name,p.unit,p.status,p.updated_at
                    FROM pdv_catalog_snapshot_active a JOIN pdv_catalog_snapshot_product p
                      ON p.terminal_id=a.terminal_id AND p.tenant_id=a.tenant_id AND p.company_id=a.company_id AND p.version=a.version
                    WHERE a.terminal_id=? AND a.tenant_id=? AND a.company_id=?
                      AND (lower(p.name) LIKE ? ESCAPE '!' OR lower(COALESCE(p.sku,'')) LIKE ? ESCAPE '!'
                           OR p.barcode=? OR EXISTS (SELECT 1 FROM pdv_catalog_snapshot_package x
                             WHERE x.terminal_id=p.terminal_id AND x.tenant_id=p.tenant_id AND x.company_id=p.company_id
                               AND x.version=p.version AND x.product_id=p.product_id AND x.barcode=?))
                    ORDER BY p.name COLLATE NOCASE,p.product_id LIMIT ? OFFSET ?
                    """)) {
                bindContext(s, context, 1); s.setString(4, term); s.setString(5, term); s.setString(6, search.strip()); s.setString(7, search.strip());
                s.setInt(8, perPage + 1); s.setInt(9, offset);
                try (ResultSet r=s.executeQuery()) { while(r.next()) rows.add(productRow(r)); }
            }
            boolean hasMore = rows.size() > perPage;
            if (hasMore) rows.removeLast();
            List<CatalogProduct> products = loadProducts(c, context, version.orElseThrow(), rows);
            String next = hasMore ? Integer.toString(offset + perPage) : null;
            return new CatalogPage(products, perPage, next, hasMore);
        });
    }

    @Override public BarcodeResolution resolveBarcode(CatalogCacheContext context, String barcode) {
        Objects.requireNonNull(context); Objects.requireNonNull(barcode);
        return transactions.inTransaction((c, tx) -> {
            long version = activeVersion(c, context).orElseThrow(() -> failure(PdvFailureKind.PRODUCT_NOT_FOUND));
            List<BarcodeMatch> matches = new ArrayList<>();
            try (PreparedStatement s=c.prepareStatement("""
                    SELECT product_id,NULL AS package_id,0 AS package_match FROM pdv_catalog_snapshot_product
                      WHERE terminal_id=? AND tenant_id=? AND company_id=? AND version=? AND barcode=?
                    UNION ALL
                    SELECT product_id,package_id,1 AS package_match FROM pdv_catalog_snapshot_package
                      WHERE terminal_id=? AND tenant_id=? AND company_id=? AND version=? AND barcode=?
                    LIMIT 2
                    """)) {
                bindContext(s,context,1); s.setLong(4,version); s.setString(5,barcode);
                bindContext(s,context,6); s.setLong(9,version); s.setString(10,barcode);
                try(ResultSet r=s.executeQuery()){ while(r.next()) matches.add(new BarcodeMatch(r.getString(1),r.getString(2),r.getInt(3)==1)); }
            }
            if(matches.isEmpty()) throw failure(PdvFailureKind.PRODUCT_NOT_FOUND);
            if(matches.size()>1) throw failure(PdvFailureKind.BARCODE_CONFLICT);
            BarcodeMatch match=matches.getFirst();
            CatalogProduct product=loadProducts(c,context,version,List.of(loadProductRow(c,context,version,match.productId()))).getFirst();
            if(!match.packageMatch()) return new BarcodeResolution(barcode,"product",BigDecimal.ONE,product.unit(),product,null);
            CatalogPackage pack=product.packages().stream().filter(p->p.id().equals(match.packageId())).findFirst()
                    .orElseThrow(()->failure(PdvFailureKind.INVALID_RESPONSE));
            return new BarcodeResolution(barcode,"package",pack.factor(),product.unit(),product,pack);
        });
    }

    @Override public Optional<ActiveCatalogInfo> activeCatalogInfo(CatalogCacheContext context) {
        Objects.requireNonNull(context);
        return transactions.inTransaction((c, tx) -> {
            try (PreparedStatement s = c.prepareStatement("""
                    SELECT a.version, a.activated_at, v.product_count
                    FROM pdv_catalog_snapshot_active a
                    JOIN pdv_catalog_snapshot_version v
                      ON v.terminal_id=a.terminal_id AND v.tenant_id=a.tenant_id
                         AND v.company_id=a.company_id AND v.version=a.version
                    WHERE a.terminal_id=? AND a.tenant_id=? AND a.company_id=?
                    """)) {
                bindContext(s, context, 1);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) return Optional.<ActiveCatalogInfo>empty();
                    return Optional.of(new ActiveCatalogInfo(
                            r.getLong(1), Instant.parse(r.getString(2)), r.getInt(3)));
                }
            }
        });
    }

    private Optional<Long> activeVersion(Connection c,CatalogCacheContext context) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT version FROM pdv_catalog_snapshot_active WHERE terminal_id=? AND tenant_id=? AND company_id=?")){
            bindContext(s,context,1); try(ResultSet r=s.executeQuery()){ return r.next()?Optional.of(r.getLong(1)):Optional.empty(); }
        }
    }

    private List<CatalogProduct> loadProducts(Connection c,CatalogCacheContext context,long version,List<ProductRow> rows) throws SQLException {
        if(rows.isEmpty()) return List.of();
        Map<String,List<CatalogPrice>> prices=new HashMap<>(); Map<String,List<CatalogPackage>> packages=new HashMap<>();
        rows.forEach(row->{prices.put(row.id(),new ArrayList<>());packages.put(row.id(),new ArrayList<>());});
        String marks=String.join(",",Collections.nCopies(rows.size(),"?"));
        try(PreparedStatement s=c.prepareStatement("SELECT product_id,currency,amount FROM pdv_catalog_snapshot_price WHERE terminal_id=? AND tenant_id=? AND company_id=? AND version=? AND product_id IN ("+marks+") ORDER BY product_id,currency")){
            bindContext(s,context,1);s.setLong(4,version);int i=5;for(ProductRow row:rows)s.setString(i++,row.id());
            try(ResultSet r=s.executeQuery()){while(r.next())prices.get(r.getString(1)).add(new CatalogPrice(new BigDecimal(r.getString(3)),r.getString(2)));}
        }
        try(PreparedStatement s=c.prepareStatement("SELECT product_id,package_id,name,barcode,factor FROM pdv_catalog_snapshot_package WHERE terminal_id=? AND tenant_id=? AND company_id=? AND version=? AND product_id IN ("+marks+") ORDER BY product_id,package_id")){
            bindContext(s,context,1);s.setLong(4,version);int i=5;for(ProductRow row:rows)s.setString(i++,row.id());
            try(ResultSet r=s.executeQuery()){while(r.next())packages.get(r.getString(1)).add(new CatalogPackage(r.getString(2),r.getString(3),r.getString(4),new BigDecimal(r.getString(5))));}
        }
        return rows.stream().map(row->new CatalogProduct(row.id(),row.sku(),row.barcode(),row.name(),row.unit(),row.status(),
                prices.get(row.id()),packages.get(row.id()),Optional.ofNullable(row.updatedAt()).map(Instant::parse))).toList();
    }

    private ProductRow loadProductRow(Connection c,CatalogCacheContext context,long version,String id)throws SQLException{
        try(PreparedStatement s=c.prepareStatement("SELECT product_id,sku,barcode,name,unit,status,updated_at FROM pdv_catalog_snapshot_product WHERE terminal_id=? AND tenant_id=? AND company_id=? AND version=? AND product_id=?")){
            bindContext(s,context,1);s.setLong(4,version);s.setString(5,id);try(ResultSet r=s.executeQuery()){if(!r.next())throw failure(PdvFailureKind.PRODUCT_NOT_FOUND);return productRow(r);}
        }
    }
    private static ProductRow productRow(ResultSet r)throws SQLException{return new ProductRow(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7));}
    private static int parseCursor(String cursor){if(cursor==null)return 0;try{int value=Integer.parseInt(cursor);if(value<0)throw new NumberFormatException();return value;}catch(NumberFormatException e){throw new IllegalArgumentException("cursor is invalid",e);}}
    private static String escapeLike(String value){return value.replace("!","!!").replace("%","!%").replace("_","!_");}
    private static PdvApiException failure(PdvFailureKind kind){return new PdvApiException(PdvApiFailure.of(kind,null));}
    private static void bindContext(PreparedStatement s,CatalogCacheContext c,int start)throws SQLException{s.setString(start,c.terminalId());s.setString(start+1,c.tenantId());s.setString(start+2,c.companyId());}
    private record ProductRow(String id,String sku,String barcode,String name,String unit,String status,String updatedAt){}
    private record BarcodeMatch(String productId,String packageId,boolean packageMatch){}
}
