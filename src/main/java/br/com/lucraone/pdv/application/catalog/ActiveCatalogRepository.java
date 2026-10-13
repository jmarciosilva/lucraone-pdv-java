package br.com.lucraone.pdv.application.catalog;

import java.util.Optional;

public interface ActiveCatalogRepository {
    /** Exact lookup scoped to the currently activated SQLite snapshot. */
    Optional<CatalogProduct> findById(CatalogCacheContext context, String productId);
    CatalogPage search(CatalogCacheContext context, String search, int perPage, String cursor);
    BarcodeResolution resolveBarcode(CatalogCacheContext context, String barcode);
}
