package br.com.lucraone.pdv.infrastructure.api.dto;

import java.util.List;
import java.math.BigDecimal;

public record BarcodeResolutionResponseDto(Data data) {
    public record Data(String barcode, String source, BigDecimal quantity, String unit,
            CatalogProductDto product,
            @com.fasterxml.jackson.annotation.JsonProperty("package") CatalogProductDto.PackageDto packageValue) {}
}
