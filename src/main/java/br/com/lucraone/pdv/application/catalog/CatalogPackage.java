package br.com.lucraone.pdv.application.catalog;

import java.math.BigDecimal;
import java.util.Objects;

public record CatalogPackage(String id, String name, String barcode, BigDecimal factor) {
    public CatalogPackage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(factor, "factor");
        if (factor.signum() <= 0) throw new IllegalArgumentException("factor must be positive");
    }
}
