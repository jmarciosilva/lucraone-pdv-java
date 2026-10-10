package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CompanyDto(
        @JsonProperty("id") String id,
        @JsonProperty("trade_name") String tradeName
) {
}
