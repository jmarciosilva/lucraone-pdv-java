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

/** Blocking orchestration: invoke only on the authentication worker, never the JavaFX thread. */
public final class CredentialRotationService {
    private static final Object ROTATION_LOCK = new Object();
    private final MachineCredentialRepository credentials;
    private final CandidateMachineCredentialRepository candidates;
    private final CredentialRotationRepository rotations;
    private final CredentialRotationProofRepository proofs;
    private final PdvBackendGateway gateway;
    private final Clock clock;

    public CredentialRotationService(MachineCredentialRepository credentials,
            CandidateMachineCredentialRepository candidateCredentials, CredentialRotationRepository rotations,
            CredentialRotationProofRepository proofs, PdvBackendGateway gateway, Clock clock) {
        this.credentials = Objects.requireNonNull(credentials);
        this.candidates = Objects.requireNonNull(candidateCredentials);
        this.rotations = Objects.requireNonNull(rotations);
        this.proofs = Objects.requireNonNull(proofs);
        this.gateway = Objects.requireNonNull(gateway);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Starts a rotation or resumes the durable operation instead of replacing it. */
    public void rotate(MachineCredential currentCredential) {
        synchronized (ROTATION_LOCK) {
            Optional<CredentialRotationRepository.PendingRotation> pending = rotations.load();
            if (pending.isEmpty()) {
                // These belong only to a prior completed rotation or to a crash before HTTP prepare.
                candidates.load().ifPresent(ignored -> candidates.delete());
                proofs.load().ifPresent(ignored -> proofs.delete());
                String operationId = UUID.randomUUID().toString();
                CredentialRotationProofRepository.Proof proof =
                        new CredentialRotationProofRepository.Proof(operationId, generateDeliverySecret());
                // The secret must be durable in the OS protected store before SQLite makes the operation visible.
                proofs.save(proof);
                rotations.save(new CredentialRotationRepository.PendingRotation(operationId, clock.instant()));
                pending = rotations.load();
            }
            resume(pending.orElseThrow(), currentCredential);
        }
    }

    /**
     * Completes any interrupted rotation before ordinary terminal validation can reject/delete the old token.
     * Returns false only when there was no pending operation. An orphan rotation proof is safe to discard:
     * it is saved before the SQLite marker, and the HTTP prepare call only starts after that marker exists.
     */
    public boolean recoverPending(MachineCredential currentCredential) {
        synchronized (ROTATION_LOCK) {
            Optional<CredentialRotationRepository.PendingRotation> pending = rotations.load();
            if (pending.isEmpty()) {
                // Crash after writing protected proof but before writing SQLite: no HTTP call could have run.
                proofs.load().ifPresent(ignored -> proofs.delete());
                candidates.load().ifPresent(ignored -> candidates.delete());
                return false;
            }
            resume(pending.orElseThrow(), currentCredential);
            return true;
        }
    }

    /**
     * Removes a superseded pending rotation only after the official recovery flow has confirmed and
     * online-validated its replacement credential. Never call this for an unverified recovery result.
     */
    public void clearAfterVerifiedAdministrativeRecovery() {
        synchronized (ROTATION_LOCK) {
            rotations.delete();
            try { candidates.delete(); } catch (RuntimeException ignored) { }
            try { proofs.delete(); } catch (RuntimeException ignored) { }
        }
    }

    private void resume(CredentialRotationRepository.PendingRotation pending, MachineCredential currentCredential) {
        String operationId = pending.operationId();
        CredentialRotationProofRepository.Proof proof = proofs.load().orElseThrow(() ->
                new TerminalAuthException(TerminalAuthException.Kind.PERSISTENCE_FAILED, null));
        if (!operationId.equals(proof.operationId())) {
            throw new TerminalAuthException(TerminalAuthException.Kind.CONTEXT_MISMATCH, null);
        }

        MachineCredential candidate;
        String requestId = null;
        Optional<StoredMachineCredential> savedCandidate = candidates.load();
        if (savedCandidate.isPresent()) {
            candidate = savedCandidate.orElseThrow().credential();
        } else {
            if (currentCredential == null) {
                throw new TerminalAuthException(TerminalAuthException.Kind.CREDENTIAL_EXPIRED, null);
            }
            CredentialRotationResult prepared;
            try {
                // Laravel makes this idempotent for the same active token, operation ID and delivery secret.
                prepared = gateway.prepareRotation(currentCredential,
                        new CredentialRotationCommand(operationId, proof.deliverySecret())).join();
            } catch (RuntimeException error) { throw backendFailure(error); }
            if (!operationId.equals(prepared.operationId())) {
                throw new TerminalAuthException(TerminalAuthException.Kind.CONTEXT_MISMATCH, prepared.requestId());
            }
            candidate = prepared.candidateCredential();
            requestId = prepared.requestId();
            if (!validBearer(candidate) || candidate.expiredAt(clock.instant())) {
                throw new TerminalAuthException(TerminalAuthException.Kind.CONTEXT_MISMATCH, requestId);
            }
            try { candidates.save(candidate); }
            catch (RuntimeException error) { throw new TerminalAuthException(TerminalAuthException.Kind.PERSISTENCE_FAILED, requestId); }
        }

        if (!validBearer(candidate)) throw new TerminalAuthException(TerminalAuthException.Kind.CONTEXT_MISMATCH, requestId);
        java.time.Instant operationalExpiry;
        try {
            // Laravel confirm is idempotent while the candidate remains valid, including after prior confirmation.
            operationalExpiry = gateway.confirmRotation(candidate, operationId).join();
        } catch (RuntimeException error) { throw backendFailure(error); }
        if (operationalExpiry == null || !operationalExpiry.isAfter(clock.instant())) {
            throw new TerminalAuthException(TerminalAuthException.Kind.CONTEXT_MISMATCH, requestId);
        }

        MachineCredential promoted = new MachineCredential(candidate.tokenType(), candidate.accessToken(), operationalExpiry);
        try {
            // Persist the confirmed validity and active credential before clearing any recovery evidence.
            candidates.save(promoted);
            StoredMachineCredential existing = credentials.load().orElse(null);
            if (existing == null || !existing.credential().accessToken().equals(promoted.accessToken())) {
                credentials.save(new StoredMachineCredential(UUID.randomUUID(), promoted));
            }
            // Delete SQLite marker first. Leftover protected items are harmless and are cleaned on next startup.
            rotations.delete();
        } catch (RuntimeException error) {
            throw new TerminalAuthException(TerminalAuthException.Kind.PERSISTENCE_FAILED, requestId);
        }
        try { candidates.delete(); } catch (RuntimeException ignored) { }
        try { proofs.delete(); } catch (RuntimeException ignored) { }
    }

    private static boolean validBearer(MachineCredential credential) {
        return credential != null && "Bearer".equals(credential.tokenType()) && credential.accessToken() != null
                && !credential.accessToken().isBlank() && credential.accessToken().chars().noneMatch(Character::isISOControl);
    }

    private static TerminalAuthException backendFailure(RuntimeException error) {
        Throwable cause = error instanceof CompletionException ? error.getCause() : error;
        String requestId = cause instanceof PdvApiException api ? api.failure().requestId().orElse(null) : null;
        if (cause instanceof PdvApiException api && (api.kind() == PdvFailureKind.UNAUTHENTICATED
                || api.kind() == PdvFailureKind.FORBIDDEN)) {
            return new TerminalAuthException(TerminalAuthException.Kind.CREDENTIAL_EXPIRED, requestId);
        }
        return new TerminalAuthException(TerminalAuthException.Kind.BACKEND_FAILURE, requestId);
    }

    private static String generateDeliverySecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        try { return HexFormat.of().formatHex(bytes); }
        finally { java.util.Arrays.fill(bytes, (byte) 0); }
    }
}
