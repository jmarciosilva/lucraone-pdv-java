package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record CatalogProductsResponseDto(List<CatalogProductDto> data, MetaDto meta) {
    public record MetaDto(@JsonProperty("per_page") Integer perPage,
            @JsonProperty("next_cursor") String nextCursor,
            @JsonProperty("has_more") Boolean hasMore) {}
}
