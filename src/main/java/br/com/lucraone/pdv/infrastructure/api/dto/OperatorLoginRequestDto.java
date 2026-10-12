package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Password never appears in logs; toString() is intentionally absent. */
public record OperatorLoginRequestDto(
        @JsonProperty("username") String username,
        @JsonProperty("password") String password
) {
    @Override public String toString() { return "OperatorLoginRequestDto[username=" + username + ",password=REDACTED]"; }
}
