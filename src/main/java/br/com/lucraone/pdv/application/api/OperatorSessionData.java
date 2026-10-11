package br.com.lucraone.pdv.application.api;

import java.time.Instant;

public record OperatorSessionData(
        String id,
        String status,
        Instant expiresAt,
        String operatorId,
        String operatorName,
        String operatorUsername,
        String operatorEmail
) {
}
