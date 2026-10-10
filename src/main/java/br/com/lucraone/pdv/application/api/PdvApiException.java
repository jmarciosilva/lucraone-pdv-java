package br.com.lucraone.pdv.application.api;

import java.util.Objects;

/**
 * Raised when a backend call cannot be completed as specified by the contract. The project signals
 * recoverable infrastructure problems with typed exceptions, so the API adapter follows the same style
 * instead of introducing a parallel result type.
 *
 * <p>The exception message is limited to the failure summary, so it stays safe to log: no body, no
 * credential, no pairing code.
 */
public final class PdvApiException extends RuntimeException {

    private final PdvApiFailure failure;

    public PdvApiException(PdvApiFailure failure) {
        super(Objects.requireNonNull(failure, "failure must not be null").summary());
        this.failure = failure;
    }

    public PdvApiException(PdvApiFailure failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure must not be null").summary(), cause);
        this.failure = failure;
    }

    public PdvApiFailure failure() {
        return failure;
    }

    public PdvFailureKind kind() {
        return failure.kind();
    }
}
