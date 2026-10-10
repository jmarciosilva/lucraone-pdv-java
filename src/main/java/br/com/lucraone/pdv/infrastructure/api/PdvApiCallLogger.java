package br.com.lucraone.pdv.infrastructure.api;

import br.com.lucraone.pdv.application.api.PdvFailureKind;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sanitised observability for backend calls. The project carries no logging framework, so this uses
 * {@code java.util.logging} rather than introducing one.
 *
 * <p>What is recorded: the logical operation, the HTTP method, the logical path, the status, the duration
 * and the correlation id. What is never recorded: the request or response body, the {@code Authorization}
 * header, the access token, the pairing code and the backend host, since a configured URL could itself
 * embed sensitive data.
 */
final class PdvApiCallLogger {

    static final String LOGGER_NAME = "br.com.lucraone.pdv.infrastructure.api";

    private static final Logger LOGGER = Logger.getLogger(LOGGER_NAME);

    void completed(String operation, String method, String path, int status, Duration duration, String requestId) {
        LOGGER.log(Level.INFO, () -> "operation=" + operation
                + ", method=" + method
                + ", path=" + path
                + ", status=" + status
                + ", duration_ms=" + duration.toMillis()
                + ", request_id=" + requestId);
    }

    void failed(
            String operation,
            String method,
            String path,
            PdvFailureKind kind,
            Duration duration,
            String requestId
    ) {
        LOGGER.log(Level.WARNING, () -> "operation=" + operation
                + ", method=" + method
                + ", path=" + path
                + ", outcome=" + kind
                + ", duration_ms=" + duration.toMillis()
                + ", request_id=" + requestId);
    }

    void retrying(String operation, String path, PdvFailureKind kind, int nextAttempt, String requestId) {
        LOGGER.log(Level.INFO, () -> "operation=" + operation
                + ", path=" + path
                + ", outcome=" + kind
                + ", retrying_attempt=" + nextAttempt
                + ", request_id=" + requestId);
    }
}
