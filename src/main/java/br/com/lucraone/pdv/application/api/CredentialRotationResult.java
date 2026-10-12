package br.com.lucraone.pdv.application.api;

import java.time.Instant;

public record CredentialRotationResult(
        String operationId,
        String status,
        Instant rotationExpiresAt,
        MachineCredential candidateCredential,
        String requestId
) {
}
