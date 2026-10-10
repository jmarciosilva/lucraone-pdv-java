package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Shared error envelope of the PDV API. {@code code} is the stable signal; {@code message} is a
 * human-readable detail that must never drive control flow.
 */
public record PdvErrorResponseDto(@JsonProperty("error") Error error) {

    public record Error(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message,
            @JsonProperty("request_id") String requestId,
            @JsonProperty("errors") Map<String, List<String>> errors
    ) {
    }
}
