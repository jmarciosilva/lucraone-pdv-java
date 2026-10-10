package br.com.lucraone.pdv.application.api;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Structured description of a failed backend call. It never carries secret material and never carries
 * the raw response body.
 *
 * @param kind            stable classification; the only value callers should branch on
 * @param code            backend {@code error.code} when the body followed the error contract
 * @param requestId       correlation id for support, preferring the value echoed by the backend
 * @param backendMessage  backend {@code error.message}. Diagnostic only: never use it for control flow
 *                        and never log it, because future messages may carry request data
 * @param retryAfter      hint from the {@code Retry-After} header, present only for rate limiting
 * @param fieldErrors     per-field messages from {@code error.errors}, empty unless {@link
 *                        PdvFailureKind#VALIDATION}
 */
public record PdvApiFailure(
        PdvFailureKind kind,
        Optional<String> code,
        Optional<String> requestId,
        Optional<String> backendMessage,
        Optional<Duration> retryAfter,
        Map<String, List<String>> fieldErrors
) {

    public PdvApiFailure {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(backendMessage, "backendMessage must not be null");
        Objects.requireNonNull(retryAfter, "retryAfter must not be null");
        fieldErrors = Map.copyOf(Objects.requireNonNull(fieldErrors, "fieldErrors must not be null"));
    }

    public static PdvApiFailure of(PdvFailureKind kind, String requestId) {
        return new PdvApiFailure(
                kind,
                Optional.empty(),
                Optional.ofNullable(requestId),
                Optional.empty(),
                Optional.empty(),
                Map.of()
        );
    }

    /**
     * Summary safe to log or show for support purposes: classification, stable code and correlation id only.
     */
    public String summary() {
        return "kind=" + kind
                + ", code=" + code.orElse("-")
                + ", request_id=" + requestId.orElse("-");
    }
}
