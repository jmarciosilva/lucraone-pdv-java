package br.com.lucraone.pdv.infrastructure.api.error;

import br.com.lucraone.pdv.application.api.PdvApiFailure;
import br.com.lucraone.pdv.application.api.PdvFailureKind;
import br.com.lucraone.pdv.infrastructure.api.dto.PdvErrorResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import javax.net.ssl.SSLException;

/**
 * Translates transport exceptions and non-success HTTP responses into the application's typed failures.
 *
 * <p>Classification is driven by the backend's stable {@code error.code} whenever the body follows the
 * error contract, and by the HTTP status otherwise. Human-readable messages are never used to decide a
 * category.
 */
public final class PdvErrorMapper {

    private static final String CODE_VALIDATION = "validation_error";
    private static final String CODE_PAIRING_FAILED = "pairing_failed";
    private static final String CODE_UNAUTHENTICATED = "unauthenticated";
    private static final String CODE_FORBIDDEN = "forbidden";
    private static final String CODE_RATE_LIMITED = "rate_limited";

    private final ObjectMapper objectMapper;
    private final Supplier<ZonedDateTime> now;

    public PdvErrorMapper(ObjectMapper objectMapper) {
        this(objectMapper, ZonedDateTime::now);
    }

    PdvErrorMapper(ObjectMapper objectMapper, Supplier<ZonedDateTime> now) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.now = Objects.requireNonNull(now, "now must not be null");
    }

    /**
     * Classifies a failure raised before any HTTP status was available.
     */
    public PdvApiFailure fromTransport(Throwable throwable, String requestId) {
        Throwable cause = unwrap(throwable);
        PdvFailureKind kind = transportKind(cause);
        return PdvApiFailure.of(kind, requestId);
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static PdvFailureKind transportKind(Throwable cause) {
        if (cause instanceof HttpConnectTimeoutException) {
            // A connect timeout is still a deadline, not an unreachable network.
            return PdvFailureKind.TIMEOUT;
        }
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
            return PdvFailureKind.TIMEOUT;
        }
        if (cause instanceof UnknownHostException
                || cause instanceof ConnectException
                || cause instanceof SSLException) {
            return PdvFailureKind.NETWORK_UNAVAILABLE;
        }
        if (cause instanceof IOException) {
            return PdvFailureKind.NETWORK_UNAVAILABLE;
        }
        return PdvFailureKind.PROTOCOL_ERROR;
    }

    /**
     * Classifies a completed exchange whose status was not successful.
     *
     * <p>Correlation precedence is the {@code X-Request-ID} response header, then the {@code
     * error.request_id} field of the error body, then the id this client sent. The header wins because the
     * contract guarantees it on every response, while {@code error.request_id} exists only in error
     * bodies; that keeps one single rule for successes and failures alike.
     *
     * @param status          HTTP status returned by the backend
     * @param body            raw response body, parsed here and never propagated or logged
     * @param retryAfter      raw {@code Retry-After} header value, when present
     * @param headerRequestId value of the {@code X-Request-ID} response header, when the backend sent one
     * @param sentRequestId   correlation id this client put on the request
     */
    public PdvApiFailure fromResponse(
            int status,
            String body,
            Optional<String> retryAfter,
            Optional<String> headerRequestId,
            String sentRequestId
    ) {
        Optional<PdvErrorResponseDto.Error> error = parse(body);
        Optional<String> code = error.map(PdvErrorResponseDto.Error::code).filter(value -> !value.isBlank());
        Optional<String> message = error.map(PdvErrorResponseDto.Error::message).filter(value -> !value.isBlank());
        Optional<String> backendRequestId = error
                .map(PdvErrorResponseDto.Error::requestId)
                .filter(value -> !value.isBlank());

        PdvFailureKind kind = kindOf(status, code);
        Map<String, List<String>> fieldErrors = kind == PdvFailureKind.VALIDATION
                ? error.map(PdvErrorResponseDto.Error::errors).map(PdvErrorMapper::copyOf).orElseGet(Map::of)
                : Map.of();

        return new PdvApiFailure(
                kind,
                code,
                Optional.of(headerRequestId.or(() -> backendRequestId).orElse(sentRequestId)),
                message,
                kind == PdvFailureKind.RATE_LIMITED ? retryAfter.flatMap(this::parseRetryAfter) : Optional.empty(),
                fieldErrors
        );
    }

    private static Map<String, List<String>> copyOf(Map<String, List<String>> errors) {
        return errors.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())
                ));
    }

    private PdvFailureKind kindOf(int status, Optional<String> code) {
        // The stable code wins whenever the backend provided one.
        if (code.isPresent()) {
            switch (code.get()) {
                case CODE_PAIRING_FAILED:
                    return PdvFailureKind.PAIRING_FAILED;
                case CODE_VALIDATION:
                    return PdvFailureKind.VALIDATION;
                case CODE_UNAUTHENTICATED:
                    return PdvFailureKind.UNAUTHENTICATED;
                case CODE_FORBIDDEN:
                    return PdvFailureKind.FORBIDDEN;
                case CODE_RATE_LIMITED:
                    return PdvFailureKind.RATE_LIMITED;
                default:
                    break;
            }
        }

        return switch (status) {
            case 401 -> PdvFailureKind.UNAUTHENTICATED;
            case 403 -> PdvFailureKind.FORBIDDEN;
            // Without a code, 422 cannot be told apart from a plain validation rejection.
            case 422 -> PdvFailureKind.VALIDATION;
            case 429 -> PdvFailureKind.RATE_LIMITED;
            default -> status >= 500 ? PdvFailureKind.SERVER_ERROR : PdvFailureKind.PROTOCOL_ERROR;
        };
    }

    private Optional<PdvErrorResponseDto.Error> parse(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            PdvErrorResponseDto dto = objectMapper.readValue(body, PdvErrorResponseDto.class);
            return Optional.ofNullable(dto).map(PdvErrorResponseDto::error);
        } catch (IOException | RuntimeException exception) {
            // A body that does not follow the error contract simply leaves the status to classify it.
            return Optional.empty();
        }
    }

    /**
     * Accepts both forms allowed by RFC 9110: delay in seconds or an HTTP date.
     */
    private Optional<Duration> parseRetryAfter(String raw) {
        String value = raw.strip();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
        } catch (NumberFormatException ignored) {
            // Not a delay; fall through to the HTTP-date form.
        }
        try {
            ZonedDateTime target = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            Duration delay = Duration.between(now.get(), target);
            return delay.isNegative() ? Optional.of(Duration.ZERO) : Optional.of(delay);
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }
}
