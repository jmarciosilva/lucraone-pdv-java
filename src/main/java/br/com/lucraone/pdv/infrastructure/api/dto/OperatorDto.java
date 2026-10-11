package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OperatorDto(
        @JsonProperty("id") String id,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("username") String username
) {
}
