package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CredentialRecoveryResponseDto(@JsonProperty("data") Data data) {
    public record Data(
            @JsonProperty("rotation") Rotation rotation,
            @JsonProperty("credential") CredentialDto credential
    ) {}
    public record Rotation(
            @JsonProperty("id") String id,
            @JsonProperty("status") String status,
            @JsonProperty("expires_at") String expiresAt
    ) {}
}
