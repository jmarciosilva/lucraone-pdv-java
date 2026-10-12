package br.com.lucraone.pdv.application.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record CatalogProduct(String id, String sku, String barcode, String name, String unit, String status,
        List<CatalogPrice> salePrices, List<CatalogPackage> packages, Optional<Instant> updatedAt) {
    public CatalogProduct {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(status, "status");
        salePrices = List.copyOf(Objects.requireNonNull(salePrices, "salePrices"));
        packages = List.copyOf(Objects.requireNonNull(packages, "packages"));
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
