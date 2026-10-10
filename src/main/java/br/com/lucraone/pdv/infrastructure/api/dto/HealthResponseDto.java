package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record HealthResponseDto(@JsonProperty("data") Data data) {

    public record Data(
            @JsonProperty("status") String status,
            @JsonProperty("api") String api,
            @JsonProperty("version") String version
    ) {
    }
}
