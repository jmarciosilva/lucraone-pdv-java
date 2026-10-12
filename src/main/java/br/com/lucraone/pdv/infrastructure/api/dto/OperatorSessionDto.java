package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OperatorSessionDto(
        @JsonProperty("id") String id,
        @JsonProperty("status") String status,
        @JsonProperty("expires_at") String expiresAt
) {
}
