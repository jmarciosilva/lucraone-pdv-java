package br.com.lucraone.pdv.application.auth;

import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.PairTerminalCommand;
import br.com.lucraone.pdv.application.api.PairedTerminal;
import br.com.lucraone.pdv.application.api.PdvApiException;
import br.com.lucraone.pdv.application.api.PdvBackendGateway;
import br.com.lucraone.pdv.application.terminal.TerminalRepository;
import br.com.lucraone.pdv.domain.terminal.Installation;
import br.com.lucraone.pdv.domain.terminal.TerminalProvisioning;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * Blocking orchestration: invoke only on the authentication worker, never the UI thread.
 * No distributed transaction is claimed. A failed write restores the previous protected record
 * or removes the new one. Previous offline proof is deliberately not restored after re-pairing.
 */
public final class TerminalProvisioningService {
    private final TerminalRepository terminals;
    private final MachineCredentialRepository credentials;
    private final TerminalValidationRepository validations;
    private final PdvBackendGateway gateway;
    private final Clock clock;

    public TerminalProvisioningService(TerminalRepository terminals, MachineCredentialRepository credentials,
            TerminalValidationRepository validations, PdvBackendGateway gateway, Clock clock) {
        this.terminals = Objects.requireNonNull(terminals);
        this.credentials = Objects.requireNonNull(credentials);
        this.validations = Objects.requireNonNull(validations);
        this.gateway = Objects.requireNonNull(gateway);
        this.clock = Objects.requireNonNull(clock);
    }

    public TerminalAuthSnapshot pair(String pairingCode) {
        Installation installation = terminals.findInstallation().orElseGet(() ->
                terminals.ensureInstallation(new Installation(UUID.randomUUID(), clock.instant())));
        PairTerminalCommand command = new PairTerminalCommand(pairingCode, installation.installationId());
        Optional<StoredMachineCredential> previous = credentials.load();
        credentials.verifyWritable();
        try {
            validations.invalidate();
        } catch (RuntimeException error) {
            throw new TerminalAuthException(TerminalAuthException.Kind.PERSISTENCE_FAILED, null);
        }

        PairedTerminal response;
        try {
            response = gateway.pairTerminal(command).join();
        } catch (RuntimeException error) {
            Throwable cause = error instanceof CompletionException ? error.getCause() : error;
            String requestId = cause instanceof PdvApiException api ? api.failure().requestId().orElse(null) : null;
            throw new TerminalAuthException(TerminalAuthException.Kind.BACKEND_FAILURE, requestId);
        }

        TerminalProvisioning refs;
        try {
            refs = TerminalContextIntegrity.provisioning(response.context(), installation.installationId(), clock.instant());
            MachineCredential credential = response.credential();
            if (!"Bearer".equals(credential.tokenType())
                    || credential.accessToken().chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException error) {
            throw new TerminalAuthException(TerminalAuthException.Kind.CONTEXT_MISMATCH, response.requestId());
        }
        if (response.credential().expiredAt(clock.instant())) {
            throw new TerminalAuthException(TerminalAuthException.Kind.CREDENTIAL_EXPIRED, response.requestId());
        }

        try {
            credentials.save(response.credential());
            terminals.saveProvisioning(refs);
        } catch (RuntimeException error) {
            try {
                if (previous.isPresent()) {
                    credentials.save(previous.orElseThrow());
                } else {
                    credentials.delete();
                }
            } catch (RuntimeException compensation) {
                throw new TerminalAuthException(TerminalAuthException.Kind.COMPENSATION_FAILED, response.requestId());
            }
            if (error instanceof CredentialStorageException storage
                    && storage.kind() == CredentialStorageException.Kind.UNAVAILABLE) {
                throw new TerminalAuthException(TerminalAuthException.Kind.SECURE_STORE_UNAVAILABLE, response.requestId());
            }
            throw new TerminalAuthException(TerminalAuthException.Kind.PERSISTENCE_FAILED, response.requestId());
        }
        return new TerminalAuthSnapshot(TerminalAuthState.CREDENTIAL_PRESENT_UNVERIFIED,
                Optional.of(response.requestId()), Optional.empty(), Optional.of(response.credential().expiresAt()), Optional.empty());
    }
}
