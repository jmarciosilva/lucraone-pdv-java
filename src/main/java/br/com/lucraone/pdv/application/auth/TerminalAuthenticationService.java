package br.com.lucraone.pdv.application.auth;

import br.com.lucraone.pdv.application.api.CurrentTerminal;
import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.PdvApiException;
import br.com.lucraone.pdv.application.api.PdvBackendGateway;
import br.com.lucraone.pdv.application.api.PdvFailureKind;
import br.com.lucraone.pdv.application.terminal.TerminalRepository;
import br.com.lucraone.pdv.domain.terminal.Installation;
import br.com.lucraone.pdv.domain.terminal.TerminalProvisioning;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Terminal identity only, not an operator session or permission to sell. Composition must supply a
 * serial worker executor; calls never block the caller. Evidence is invalidated BEFORE online reads:
 * a crash, rejection or failed deletion can never silently reuse proof from before that read.
 */
public final class TerminalAuthenticationService {
    private final TerminalRepository terminals;
    private final MachineCredentialRepository credentials;
    private final TerminalValidationRepository validations;
    private final Optional<PdvBackendGateway> gateway;
    private final String backendIdentity;
    private final Clock clock;
    private final Executor executor;

    public TerminalAuthenticationService(TerminalRepository terminals, MachineCredentialRepository credentials,
            TerminalValidationRepository validations, Optional<PdvBackendGateway> gateway,
            String backendIdentity, Clock clock, Executor executor) {
        this.terminals = Objects.requireNonNull(terminals);
        this.credentials = Objects.requireNonNull(credentials);
        this.validations = Objects.requireNonNull(validations);
        this.gateway = Objects.requireNonNull(gateway);
        this.backendIdentity = Objects.requireNonNull(backendIdentity);
        this.clock = Objects.requireNonNull(clock);
        this.executor = Objects.requireNonNull(executor);
    }

    public CompletableFuture<TerminalAuthSnapshot> restore() {
        return execute(this::restoreBlocking);
    }

    public CompletableFuture<TerminalAuthSnapshot> pair(String code) {
        return execute(() -> {
            if (gateway.isEmpty()) return TerminalAuthSnapshot.of(TerminalAuthState.BACKEND_NOT_CONFIGURED);
            try {
                new TerminalProvisioningService(terminals, credentials, validations, gateway.orElseThrow(), clock).pair(code);
            } catch (TerminalAuthException error) {
                TerminalAuthState state = switch (error.kind()) {
                    case CONTEXT_MISMATCH -> TerminalAuthState.CONTEXT_MISMATCH;
                    case CREDENTIAL_EXPIRED -> TerminalAuthState.REAUTHENTICATION_REQUIRED;
                    case COMPENSATION_FAILED -> TerminalAuthState.INCONSISTENT_STATE;
                    case SECURE_STORE_UNAVAILABLE -> TerminalAuthState.SECURE_STORE_UNAVAILABLE;
                    default -> TerminalAuthState.VALIDATION_FAILED;
                };
                return new TerminalAuthSnapshot(state, error.requestId(), Optional.empty(), Optional.empty(), Optional.empty());
            }
            return restoreBlocking();
        });
    }

    /**
     * Loads the stored machine credential synchronously on the caller's thread. Used by the UI to
     * obtain the credential after authentication so it can be passed to operator login. Returns
     * {@code null} when no credential is stored or the store is unavailable.
     */
    public MachineCredential loadStoredCredential() {
        return credentials.load().map(stored -> stored.credential()).orElse(null);
    }

    /** Non-secret local references for the suspended-terminal screen and recovery request. */
    public LocalTerminalIdentity localIdentity() {
        Installation installation = terminals.findInstallation().orElseThrow();
        TerminalProvisioning provisioning = terminals.findProvisioning().orElseThrow();
        return new LocalTerminalIdentity(installation.installationId(), provisioning.terminalId(), provisioning.branchId());
    }

    public CompletableFuture<TerminalAuthSnapshot> forget() {
        return execute(() -> {
            // Fail before deleting: the installation and provisioning references remain intact.
            validations.invalidate();
            try {
                credentials.delete();
            } catch (CredentialStorageException error) {
                return storageFailure(error);
            }
            return TerminalAuthSnapshot.of(terminals.findProvisioning().isPresent()
                    ? TerminalAuthState.PROVISIONED_NO_CREDENTIAL : TerminalAuthState.NOT_PROVISIONED);
        });
    }

    private CompletableFuture<TerminalAuthSnapshot> execute(Supplier<TerminalAuthSnapshot> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.get();
            } catch (CredentialStorageException error) {
                return storageFailure(error);
            } catch (RuntimeException error) {
                return TerminalAuthSnapshot.of(TerminalAuthState.LOCAL_STATE_UNAVAILABLE);
            }
        }, executor);
    }

    private static TerminalAuthSnapshot storageFailure(CredentialStorageException error) {
        return TerminalAuthSnapshot.of(error.kind() == CredentialStorageException.Kind.UNAVAILABLE
                ? TerminalAuthState.SECURE_STORE_UNAVAILABLE : TerminalAuthState.INCONSISTENT_STATE);
    }

    private TerminalAuthSnapshot restoreBlocking() {
        Installation installation = terminals.findInstallation().orElseGet(() ->
                terminals.ensureInstallation(new Installation(UUID.randomUUID(), clock.instant())));
        Optional<TerminalProvisioning> provisioning = terminals.findProvisioning();
        Optional<StoredMachineCredential> loaded = credentials.load();
        if (loaded.isEmpty()) {
            return TerminalAuthSnapshot.of(provisioning.isPresent()
                    ? TerminalAuthState.PROVISIONED_NO_CREDENTIAL : TerminalAuthState.NOT_PROVISIONED);
        }
        if (provisioning.isEmpty()) return TerminalAuthSnapshot.of(TerminalAuthState.INCONSISTENT_STATE);
        StoredMachineCredential stored = loaded.orElseThrow();
        MachineCredential credential = stored.credential();
        if (credential.expiredAt(clock.instant())) {
            validations.invalidate();
            return snapshot(TerminalAuthState.REAUTHENTICATION_REQUIRED, credential, null, null);
        }
        if (gateway.isEmpty()) {
            return snapshot(TerminalAuthState.CREDENTIAL_PRESENT_UNVERIFIED, credential, null, null);
        }

        TerminalProvisioning refs = provisioning.orElseThrow();
        Optional<TerminalValidation> prior = validations.load();
        // Durability barrier BEFORE any backend response can reject this token.
        validations.invalidate();
        CurrentTerminal response;
        try {
            response = gateway.orElseThrow().currentTerminal(credential).join();
        } catch (RuntimeException error) {
            Throwable cause = error instanceof CompletionException ? error.getCause() : error;
            if (cause instanceof PdvApiException api) {
                return onlineFailure(api, stored, installation, refs, prior);
            }
            return snapshot(TerminalAuthState.VALIDATION_FAILED, credential, null, null);
        }
        if (credential.expiredAt(clock.instant())) {
            return snapshot(TerminalAuthState.REAUTHENTICATION_REQUIRED, credential, response.requestId(), null);
        }
        if (!TerminalContextIntegrity.matches(response.context(), installation.installationId(), refs)
                || !credential.expiresAt().equals(response.credentialExpiresAt())) {
            return snapshot(TerminalAuthState.CONTEXT_MISMATCH, credential, response.requestId(), null);
        }
        validations.save(new TerminalValidation(stored.generation(), installation.installationId(),
                refs.terminalId(), refs.branchId(), backendIdentity, clock.instant()));
        if (credential.expiredAt(clock.instant())) {
            validations.invalidate();
            return snapshot(TerminalAuthState.REAUTHENTICATION_REQUIRED, credential, response.requestId(), null);
        }
        return snapshot(TerminalAuthState.AUTHENTICATED, credential, response.requestId(), null);
    }

    private TerminalAuthSnapshot onlineFailure(PdvApiException error, StoredMachineCredential stored,
            Installation installation, TerminalProvisioning refs, Optional<TerminalValidation> prior) {
        PdvFailureKind kind = error.kind();
        String requestId = error.failure().requestId().orElse(null);
        MachineCredential credential = stored.credential();
        if (kind == PdvFailureKind.UNAUTHENTICATED || kind == PdvFailureKind.FORBIDDEN) {
            try {
                credentials.delete();
            } catch (RuntimeException deletionFailure) {
                return snapshot(TerminalAuthState.INCONSISTENT_STATE, credential, requestId, kind);
            }
            return snapshot(TerminalAuthState.REAUTHENTICATION_REQUIRED, credential, requestId, kind);
        }
        if (credential.expiredAt(clock.instant())) {
            return snapshot(TerminalAuthState.REAUTHENTICATION_REQUIRED, credential, requestId, kind);
        }
        if (kind == PdvFailureKind.NETWORK_UNAVAILABLE || kind == PdvFailureKind.TIMEOUT) {
            boolean eligible = prior.filter(validation -> validation.generation().equals(stored.generation())
                    && validation.installationId().equals(installation.installationId())
                    && validation.terminalId().equals(refs.terminalId())
                    && validation.branchId().equals(refs.branchId())
                    && validation.backendIdentity().equals(backendIdentity)
                    && !validation.validatedAt().isAfter(clock.instant())).isPresent();
            if (eligible) {
                validations.save(prior.orElseThrow());
                if (credential.expiredAt(clock.instant())) {
                    validations.invalidate();
                    return snapshot(TerminalAuthState.REAUTHENTICATION_REQUIRED, credential, requestId, kind);
                }
                return snapshot(TerminalAuthState.OFFLINE_LIMITED, credential, requestId, kind);
            }
            return snapshot(TerminalAuthState.CREDENTIAL_PRESENT_UNVERIFIED, credential, requestId, kind);
        }
        return snapshot(TerminalAuthState.VALIDATION_FAILED, credential, requestId, kind,
                error.failure().retryAfter().orElse(null));
    }

    private static TerminalAuthSnapshot snapshot(TerminalAuthState state, MachineCredential credential,
            String requestId, PdvFailureKind kind) {
        return new TerminalAuthSnapshot(state, Optional.ofNullable(requestId),
                Optional.ofNullable(kind), Optional.of(credential.expiresAt()), Optional.empty());
    }

    private static TerminalAuthSnapshot snapshot(TerminalAuthState state, MachineCredential credential,
            String requestId, PdvFailureKind kind, java.time.Duration retryAfter) {
        return new TerminalAuthSnapshot(state, Optional.ofNullable(requestId), Optional.ofNullable(kind),
                Optional.of(credential.expiresAt()), Optional.ofNullable(retryAfter));
    }
}
