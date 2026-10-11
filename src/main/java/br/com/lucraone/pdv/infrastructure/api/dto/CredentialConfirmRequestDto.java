package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CredentialConfirmRequestDto(
        @JsonProperty("operation_id") String operationId
) {
}
