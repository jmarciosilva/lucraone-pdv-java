package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public record CatalogProductDto(String id, String sku, String barcode, String name, String unit, String status,
        @JsonProperty("sale_prices") List<SalePriceDto> salePrices,
        List<PackageDto> packages,
        @JsonProperty("updated_at") String updatedAt) {
    public record SalePriceDto(String amount, String currency) {}
    public record PackageDto(String id, String name, String barcode, BigDecimal factor) {}
}
