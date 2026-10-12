package br.com.lucraone.pdv.application.auth;

import br.com.lucraone.pdv.application.api.CredentialRotationResult;
import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.PdvApiException;
import br.com.lucraone.pdv.application.api.PdvBackendGateway;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Official admin-code recovery flow. Invoke through its asynchronous method, never on JavaFX. */
public final class CredentialRecoveryService {
    private final MachineCredentialRepository credentials;
    private final CandidateMachineCredentialRepository candidates;
    private final CredentialRecoveryRepository proofs;
    private final PdvBackendGateway gateway;
    private final TerminalAuthenticationService authentication;
    private final Clock clock;
    private final Executor executor;

    public CredentialRecoveryService(MachineCredentialRepository credentials,
            CandidateMachineCredentialRepository candidates, CredentialRecoveryRepository proofs,
            PdvBackendGateway gateway, TerminalAuthenticationService authentication, Clock clock, Executor executor) {
        this.credentials = Objects.requireNonNull(credentials); this.candidates = Objects.requireNonNull(candidates);
        this.proofs = Objects.requireNonNull(proofs); this.gateway = Objects.requireNonNull(gateway);
        this.authentication = Objects.requireNonNull(authentication); this.clock = Objects.requireNonNull(clock);
        this.executor = Objects.requireNonNull(executor);
    }

    public CompletableFuture<TerminalAuthSnapshot> recover(String recoveryCode) {
        return CompletableFuture.runAsync(() -> recoverBlocking(recoveryCode), executor)
                .thenCompose(ignored -> authentication.restore());
    }

    private void recoverBlocking(String code) {
        if (code == null || code.length() != 19) throw new IllegalArgumentException("Recovery code has invalid shape.");
        LocalTerminalIdentity identity = authentication.localIdentity();
        CredentialRecoveryRepository.Proof proof = proofs.load().orElse(null);
        if (proof == null) {
            proof = new CredentialRecoveryRepository.Proof(code, identity.installationId(), generateSecret());
            proofs.save(proof); // Durable protected proof before the public recovery request.
        } else if (!proof.recoveryCode().equals(code) || !proof.installationId().equals(identity.installationId())) {
            throw new IllegalStateException("A different recovery attempt is already pending.");
        }

        Optional<StoredMachineCredential> savedCandidate = candidates.load();
        CredentialRotationResult result = null;
        MachineCredential candidate;
        String operationId;
        if (proof.operationId() != null && savedCandidate.isPresent()) {
            candidate = savedCandidate.orElseThrow().credential();
            operationId = proof.operationId();
        } else {
            try {
                result = gateway.recoverCredential(proof.recoveryCode(), proof.installationId().toString(),
                        proof.deliverySecret()).join();
            } catch (RuntimeException error) { throw safe(error); }
            proof = proof.withOperationId(result.operationId());
            proofs.save(proof);
            candidate = result.candidateCredential();
            operationId = result.operationId();
        }
        if (!"Bearer".equals(candidate.tokenType()) || candidate.expiredAt(clock.instant()))
            throw new IllegalStateException("Backend returned an invalid recovery credential.");
        candidates.save(candidate); // Persist candidate before confirmation.
        try {
            java.time.Instant operationalExpiry = gateway.confirmRecoveredCredential(candidate, operationId).join();
            if (operationalExpiry == null || !operationalExpiry.isAfter(clock.instant()))
                throw new IllegalStateException("Backend returned an expired confirmed credential.");
            candidate = new MachineCredential(candidate.tokenType(), candidate.accessToken(), operationalExpiry);
            candidates.save(candidate);
        } catch (RuntimeException error) { throw safe(error); }
        credentials.save(new StoredMachineCredential(UUID.randomUUID(), candidate));
        proofs.delete();
        candidates.delete();
    }

    private static RuntimeException safe(RuntimeException error) {
        Throwable cause = error instanceof CompletionException ? error.getCause() : error;
        if (cause instanceof PdvApiException api) return api;
        return new IllegalStateException("Credential recovery failed.");
    }

    private static String generateSecret() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        try { return HexFormat.of().formatHex(secret); }
        finally { java.util.Arrays.fill(secret, (byte) 0); }
    }
}
