package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TerminalDto(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("status") String status,
        @JsonProperty("installation_id") String installationId
) {
}
