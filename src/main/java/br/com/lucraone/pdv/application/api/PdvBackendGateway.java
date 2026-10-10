package br.com.lucraone.pdv.application.api;

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
}
