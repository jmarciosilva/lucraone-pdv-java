package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TenantDto(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name
) {
}
