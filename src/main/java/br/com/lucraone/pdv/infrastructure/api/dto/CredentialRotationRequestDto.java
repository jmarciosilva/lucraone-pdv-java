package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The delivery_secret is never stored or logged. */
public record CredentialRotationRequestDto(
        @JsonProperty("operation_id") String operationId,
        @JsonProperty("delivery_secret") String deliverySecret
) {
    @Override public String toString() { return "CredentialRotationRequestDto[operationId=" + operationId + ",deliverySecret=REDACTED]"; }
}
