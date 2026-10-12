package br.com.lucraone.pdv.application.api;

import java.util.List;

public record OperatorListResult(String branchId, List<OperatorInfo> operators, String requestId) {
}
