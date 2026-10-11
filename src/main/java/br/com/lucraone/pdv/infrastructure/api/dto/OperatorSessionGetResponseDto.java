package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OperatorSessionGetResponseDto(@JsonProperty("data") Data data) {

    public record Data(
            @JsonProperty("session") OperatorSessionDto session,
            @JsonProperty("operator") OperatorDataDto operator,
            @JsonProperty("context") com.fasterxml.jackson.databind.JsonNode context,
            @JsonProperty("capabilities") com.fasterxml.jackson.databind.JsonNode capabilities,
            @JsonProperty("policy") com.fasterxml.jackson.databind.JsonNode policy,
            @JsonProperty("offline") com.fasterxml.jackson.databind.JsonNode offline
    ) {
    }
}
