package br.com.lucraone.pdv.infrastructure.api;

import br.com.lucraone.pdv.application.ApplicationMetadata;
import java.time.Duration;
import java.util.Objects;

/**
 * Transport tuning for the PDV API client, kept in one place so no timeout is hidden inside a call site.
 *
 * <p>The defaults are deliberately short: a point of sale must report an unreachable backend quickly
 * instead of leaving the operator waiting.
 *
 * @param connectTimeout deadline to establish the TCP/TLS connection
 * @param requestTimeout deadline for the complete request/response exchange
 * @param userAgent      identification sent to the backend; carries the build version, never a secret
 */
public record PdvApiSettings(Duration connectTimeout, Duration requestTimeout, String userAgent) {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final String USER_AGENT_PREFIX = "LucraOne-PDV/";

    public PdvApiSettings {
        Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
        Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        Objects.requireNonNull(userAgent, "userAgent must not be null");
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (userAgent.isBlank()) {
            throw new IllegalArgumentException("userAgent must not be blank");
        }
    }

    public static PdvApiSettings defaults() {
        return new PdvApiSettings(
                DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_REQUEST_TIMEOUT,
                USER_AGENT_PREFIX + ApplicationMetadata.version()
        );
    }

    public PdvApiSettings withConnectTimeout(Duration connectTimeout) {
        return new PdvApiSettings(connectTimeout, requestTimeout, userAgent);
    }

    public PdvApiSettings withRequestTimeout(Duration requestTimeout) {
        return new PdvApiSettings(connectTimeout, requestTimeout, userAgent);
    }
}
