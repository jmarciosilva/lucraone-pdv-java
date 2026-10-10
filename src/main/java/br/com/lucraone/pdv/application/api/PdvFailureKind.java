package br.com.lucraone.pdv.application.api;

/**
 * Stable, transport-agnostic classification of a backend call failure. Callers branch on this
 * instead of on HTTP status codes or on human-readable backend messages.
 */
public enum PdvFailureKind {

    /** The backend could not be reached at all: DNS, connection refused, TLS handshake. */
    NETWORK_UNAVAILABLE,

    /** The connection or the request exceeded its configured deadline. */
    TIMEOUT,

    /** The exchange completed but violated the expected protocol, including unexpected status codes. */
    PROTOCOL_ERROR,

    /** HTTP 401. The backend deliberately does not disclose the underlying cause. */
    UNAUTHENTICATED,

    /** HTTP 403: authenticated but not allowed to perform the operation. */
    FORBIDDEN,

    /** HTTP 422 with {@code validation_error}: the request payload was rejected field by field. */
    VALIDATION,

    /** HTTP 422 with {@code pairing_failed}: the pairing attempt itself was refused. */
    PAIRING_FAILED,

    /** HTTP 429: the caller must slow down; a retry hint may be available. */
    RATE_LIMITED,

    /** HTTP 500 or any other server-side failure. */
    SERVER_ERROR,

    /** A successful status carrying a body that could not be understood or was incomplete. */
    INVALID_RESPONSE
}
