package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OperatorSessionGetResponseDto(@JsonProperty("data") Data data) {

    public record Data(
            @JsonProperty("session") OperatorSessionDto session
    ) {
    }
}
