package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OperatorDataDto(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("email") String email
) {
}
