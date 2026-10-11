package br.com.lucraone.pdv.application.auth;

import br.com.lucraone.pdv.application.api.CredentialRotationCommand;
import br.com.lucraone.pdv.application.api.CredentialRotationResult;
import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.PdvApiException;
import br.com.lucraone.pdv.application.api.PdvBackendGateway;
import br.com.lucraone.pdv.application.api.PdvFailureKind;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * Blocking orchestration: invoke only on the authentication worker, never the UI thread.
 *
 * <p>Rotation flow:
 * <ol>
 *   <li>Generate an operation_id.</li>
 *   <li>Persist the pending rotation in SQLite BEFORE calling the backend (crash recovery).</li>
 *   <li>Call {@code POST /credential-rotations} with the current machine credential.</li>
 *   <li>Obtain the candidate credential from the response.</li>
 *   <li>Persist the candidate credential in the SecretStore under the candidate key.</li>
 *   <li>Call {@code POST /credential-rotations/{id}/confirm} with the candidate credential.</li>
 *   <li>Replace the machine credential in the SecretStore with the confirmed candidate.</li>
 *   <li>Delete the pending rotation record from SQLite.</li>
 * </ol>
 *
 * <p>No distributed atomicity is claimed. If a step fails, the in-progress record remains in SQLite
 * and the caller may attempt recovery or report an inconsistent state.
 */
public final class CredentialRotationService {

    private final MachineCredentialRepository credentials;
    private final CandidateMachineCredentialRepository candidateCredentials;
    private final CredentialRotationRepository rotations;
    private final PdvBackendGateway gateway;
    private final Clock clock;

    public CredentialRotationService(
            MachineCredentialRepository credentials,
            CandidateMachineCredentialRepository candidateCredentials,
            CredentialRotationRepository rotations,
            PdvBackendGateway gateway,
            Clock clock) {
        this.credentials = Objects.requireNonNull(credentials);
        this.candidateCredentials = Objects.requireNonNull(candidateCredentials);
        this.rotations = Objects.requireNonNull(rotations);
        this.gateway = Objects.requireNonNull(gateway);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Runs the full rotation and, on success, replaces the current machine credential with the
     * promoted candidate. Returns normally on success; throws {@link TerminalAuthException} on failure.
     */
    public void rotate(MachineCredential currentCredential) {
        String operationId = UUID.randomUUID().toString();
        rotations.save(new CredentialRotationRepository.PendingRotation(operationId, clock.instant()));

        CredentialRotationResult rotationResult;
        try {
            rotationResult = gateway.prepareRotation(currentCredential,
                    new CredentialRotationCommand(operationId, generateDeliverySecret())).join();
        } catch (RuntimeException error) {
            Throwable cause = error instanceof CompletionException ? error.getCause() : error;
            String requestId = cause instanceof PdvApiException api
                    ? api.failure().requestId().orElse(null) : null;
            if (cause instanceof PdvApiException api
                    && api.kind() == PdvFailureKind.ROTATION_CONFLICT) {
                throw new TerminalAuthException(TerminalAuthException.Kind.BACKEND_FAILURE, requestId);
            }
            throw new TerminalAuthException(TerminalAuthException.Kind.BACKEND_FAILURE, requestId);
        }

        MachineCredential candidate = rotationResult.candidateCredential();
        if (!"Bearer".equals(candidate.tokenType()) || candidate.expiredAt(clock.instant())) {
            throw new TerminalAuthException(TerminalAuthException.Kind.CONTEXT_MISMATCH,
                    rotationResult.requestId());
        }

        // Persist candidate BEFORE confirming so we can recover after a crash.
        Optional<StoredMachineCredential> previousCandidate;
        try {
            previousCandidate = candidateCredentials.load();
            candidateCredentials.save(candidate);
        } catch (RuntimeException error) {
            throw new TerminalAuthException(TerminalAuthException.Kind.PERSISTENCE_FAILED,
                    rotationResult.requestId());
        }

        try {
            gateway.confirmRotation(candidate, rotationResult.operationId()).join();
        } catch (RuntimeException error) {
            // Confirm failed: remove candidate credential and leave pending record for diagnostics.
            try {
                candidateCredentials.delete();
            } catch (RuntimeException ignored) {}
            Throwable cause = error instanceof CompletionException ? error.getCause() : error;
            String requestId = cause instanceof PdvApiException api
                    ? api.failure().requestId().orElse(null) : null;
            throw new TerminalAuthException(TerminalAuthException.Kind.BACKEND_FAILURE, requestId);
        }

        // Promote candidate to current credential.
        try {
            credentials.save(new StoredMachineCredential(UUID.randomUUID(), candidate));
        } catch (RuntimeException error) {
            throw new TerminalAuthException(TerminalAuthException.Kind.PERSISTENCE_FAILED,
                    rotationResult.requestId());
        }

        // Best-effort cleanup.
        try { candidateCredentials.delete(); } catch (RuntimeException ignored) {}
        try { rotations.delete(); } catch (RuntimeException ignored) {}
    }

    private static String generateDeliverySecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
