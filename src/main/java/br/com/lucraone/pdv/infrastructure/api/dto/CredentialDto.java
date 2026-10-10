package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Credential block of the PDV contract. Pairing fills all three fields; the authenticated terminal read
 * returns only {@code expires_at}, never an access token.
 *
 * <p>{@link #toString()} is redacted so the raw token cannot reach a log through a record's generated
 * representation.
 */
public record CredentialDto(
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_at") String expiresAt
) {

    @Override
    public String toString() {
        return "CredentialDto[token_type=" + tokenType
                + ", access_token=" + (accessToken == null ? "null" : "***")
                + ", expires_at=" + expiresAt + "]";
    }
}
