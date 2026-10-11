package br.com.lucraone.pdv.application.api;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Application port for the LucraOne PDV backend. It is deliberately transport-agnostic: no HTTP type,
 * no JSON type and no operating-system detail appears in this interface, so the application layer stays
 * portable across Windows, Linux and macOS.
 *
 * <p>Every method is asynchronous so that no caller, and in particular no JavaFX thread, blocks on the
 * network. Failures complete the returned future exceptionally with {@link PdvApiException}, which
 * carries a {@link PdvApiFailure}.
 */
public interface PdvBackendGateway {

    /** Unauthenticated liveness and contract-version check. */
    CompletableFuture<BackendHealth> health();

    /** Exchanges a single-use pairing code for a machine credential. Never retried automatically. */
    CompletableFuture<PairedTerminal> pairTerminal(PairTerminalCommand command);

    /** Reads the terminal context authorised by the given credential. */
    CompletableFuture<CurrentTerminal> currentTerminal(MachineCredential credential);

    /** Lists operators eligible to open a session at this terminal's branch. */
    CompletableFuture<OperatorListResult> listOperators(MachineCredential credential);

    /** Authenticates an operator and opens a new session. Never retried automatically. */
    CompletableFuture<OperatorLoginResult> loginOperator(MachineCredential credential, OperatorLoginCommand command);

    /** Fetches an existing operator session by token. */
    CompletableFuture<OperatorSessionData> getOperatorSession(MachineCredential credential, String sessionToken);

    /** Revalidates (slides) an existing operator session window. Never retried automatically. */
    CompletableFuture<OperatorSessionData> revalidateOperatorSession(MachineCredential credential, String sessionToken);

    /** Logs out the operator and revokes the given session. Never retried automatically. */
    CompletableFuture<Void> logoutOperator(MachineCredential credential, String sessionToken);

    /** Initiates a credential rotation — obtains a candidate credential. Never retried automatically. */
    CompletableFuture<CredentialRotationResult> prepareRotation(MachineCredential credential,
            CredentialRotationCommand command);

    /** Confirms the rotation using the candidate credential and returns the confirmed-at instant. Never retried. */
    CompletableFuture<Instant> confirmRotation(MachineCredential candidateCredential, String operationId);
}
