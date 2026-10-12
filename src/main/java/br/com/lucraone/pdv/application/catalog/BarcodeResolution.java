package br.com.lucraone.pdv.application.catalog;

import java.math.BigDecimal;
import java.util.Objects;

public record BarcodeResolution(String barcode, String source, BigDecimal quantity, String unit,
        CatalogProduct product, CatalogPackage commercialPackage) {
    public BarcodeResolution {
        Objects.requireNonNull(barcode, "barcode");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(product, "product");
        if (barcode.isEmpty() || barcode.length() > 14 || quantity.signum() <= 0)
            throw new IllegalArgumentException("invalid barcode resolution");
    }
}
