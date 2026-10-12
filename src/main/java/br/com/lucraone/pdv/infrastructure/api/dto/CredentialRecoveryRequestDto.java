package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CredentialRecoveryRequestDto(
        @JsonProperty("recovery_code") String recoveryCode,
        @JsonProperty("installation_id") String installationId,
        @JsonProperty("delivery_secret") String deliverySecret
) {
    @Override public String toString() { return "CredentialRecoveryRequestDto[redacted]"; }
}
