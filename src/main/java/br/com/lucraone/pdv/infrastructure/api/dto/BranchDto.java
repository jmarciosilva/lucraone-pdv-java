package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BranchDto(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("code") String code
) {
}
