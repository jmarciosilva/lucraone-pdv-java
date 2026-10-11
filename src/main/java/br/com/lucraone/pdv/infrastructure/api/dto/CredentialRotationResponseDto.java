package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CredentialRotationResponseDto(@JsonProperty("data") Data data) {

    public record Data(
            @JsonProperty("operation_id") String operationId,
            @JsonProperty("status") String status,
            @JsonProperty("rotation_expires_at") String rotationExpiresAt,
            @JsonProperty("credential") CredentialDto credential
    ) {
    }
}
