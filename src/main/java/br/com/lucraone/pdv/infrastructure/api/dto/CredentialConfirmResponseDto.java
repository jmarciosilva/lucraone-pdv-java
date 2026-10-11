package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CredentialConfirmResponseDto(@JsonProperty("data") Data data) {

    public record Data(
            @JsonProperty("confirmed_at") String confirmedAt
    ) {
    }
}
