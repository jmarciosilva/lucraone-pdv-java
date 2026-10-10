package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Pairing payload. {@link #toString()} is redacted because the pairing code is single-use and sensitive.
 */
public record PairTerminalRequestDto(
        @JsonProperty("pairing_code") String pairingCode,
        @JsonProperty("installation_id") String installationId
) {

    @Override
    public String toString() {
        return "PairTerminalRequestDto[pairing_code=***, installation_id=" + installationId + "]";
    }
}
