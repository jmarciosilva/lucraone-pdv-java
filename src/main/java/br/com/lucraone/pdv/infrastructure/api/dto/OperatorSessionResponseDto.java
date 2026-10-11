package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OperatorSessionResponseDto(@JsonProperty("data") Data data) {

    public record Data(
            @JsonProperty("session") OperatorSessionDto session,
            @JsonProperty("credential") OperatorSessionCredentialDto credential
    ) {
    }
}
