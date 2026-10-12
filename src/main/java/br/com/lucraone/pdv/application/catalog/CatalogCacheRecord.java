package br.com.lucraone.pdv.application.catalog;

import java.time.Instant;
import java.util.Objects;

public record CatalogCacheRecord(CatalogProduct product, Instant queriedAt) {
    public CatalogCacheRecord { Objects.requireNonNull(product); Objects.requireNonNull(queriedAt); }
}
