package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Session credential; access token is never stored or logged. */
public record OperatorSessionCredentialDto(
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_at") String expiresAt
) {
    @Override public String toString() { return "OperatorSessionCredentialDto[tokenType=" + tokenType + ",accessToken=REDACTED]"; }
}
