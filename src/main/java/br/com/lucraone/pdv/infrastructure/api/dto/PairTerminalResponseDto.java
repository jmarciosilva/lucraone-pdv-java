package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PairTerminalResponseDto(@JsonProperty("data") Data data) {

    public record Data(
            @JsonProperty("terminal") TerminalDto terminal,
            @JsonProperty("tenant") TenantDto tenant,
            @JsonProperty("company") CompanyDto company,
            @JsonProperty("branch") BranchDto branch,
            @JsonProperty("credential") CredentialDto credential
    ) {
    }
}
