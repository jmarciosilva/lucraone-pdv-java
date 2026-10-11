package br.com.lucraone.pdv.infrastructure.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record OperatorsListResponseDto(@JsonProperty("data") Data data) {

    public record Data(
            @JsonProperty("branch_id") String branchId,
            @JsonProperty("operators") List<OperatorDto> operators
    ) {
    }
}
