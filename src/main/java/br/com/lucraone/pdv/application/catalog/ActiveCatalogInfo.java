package br.com.lucraone.pdv.application.catalog;

import java.time.Instant;
import java.util.Objects;

/**
 * Non-secret metadata of the catalog snapshot currently activated for a terminal, used to show the
 * operator which catalog version is in effect and when it was applied.
 */
public record ActiveCatalogInfo(long version, Instant activatedAt, int productCount) {
    public ActiveCatalogInfo {
        Objects.requireNonNull(activatedAt, "activatedAt must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        if (productCount < 0) {
            throw new IllegalArgumentException("productCount must not be negative");
        }
    }
}
