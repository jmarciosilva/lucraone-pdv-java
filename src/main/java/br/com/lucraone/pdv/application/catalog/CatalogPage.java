package br.com.lucraone.pdv.application.catalog;

import java.util.List;
import java.util.Objects;

public record CatalogPage(List<CatalogProduct> products, int perPage, String nextCursor, boolean hasMore) {
    public CatalogPage {
        products = List.copyOf(Objects.requireNonNull(products, "products"));
        if (perPage < 1 || perPage > 100) throw new IllegalArgumentException("perPage must be from 1 to 100");
        if (hasMore && (nextCursor == null || nextCursor.isBlank()))
            throw new IllegalArgumentException("a next cursor is required when more pages exist");
        if (!hasMore && nextCursor != null) throw new IllegalArgumentException("final page cursor must be null");
    }
}
