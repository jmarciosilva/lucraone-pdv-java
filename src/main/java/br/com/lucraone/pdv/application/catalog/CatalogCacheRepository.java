package br.com.lucraone.pdv.application.catalog;

import java.time.Instant;
import java.util.List;

public interface CatalogCacheRepository {
    void upsert(CatalogCacheContext context, List<CatalogProduct> products, Instant queriedAt);
    List<CatalogCacheRecord> findAll(CatalogCacheContext context);
}
