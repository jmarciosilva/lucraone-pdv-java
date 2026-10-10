package br.com.lucraone.pdv.infrastructure.api;

import br.com.lucraone.pdv.application.api.BackendHealth;
import br.com.lucraone.pdv.application.api.CurrentTerminal;
import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.PairTerminalCommand;
import br.com.lucraone.pdv.application.api.PairedTerminal;
import br.com.lucraone.pdv.application.api.PdvApiException;
import br.com.lucraone.pdv.application.api.PdvApiFailure;
import br.com.lucraone.pdv.application.api.PdvBackendGateway;
import br.com.lucraone.pdv.application.api.PdvFailureKind;
import br.com.lucraone.pdv.infrastructure.api.dto.PairTerminalRequestDto;
import br.com.lucraone.pdv.infrastructure.api.error.PdvErrorMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * HTTPS adapter for {@link PdvBackendGateway}, built on the JDK's own {@link HttpClient}: Java already
 * ships a modern client, so no third-party HTTP library is introduced.
 *
 * <p>Only portable Java APIs are used. Nothing here inspects the operating system, reads an environment
 * variable or touches the filesystem, so the same adapter runs unchanged on Windows, Linux and macOS.
 *
 * <p>TLS is left entirely to the platform default: no {@code SSLContext}, {@code TrustManager} or
 * hostname verifier is replaced, so certificate and hostname validation stay in force. Redirects are
 * never followed, which also prevents an {@code Authorization} header from being replayed to another host.
 *
 * <p>Every call is asynchronous, so no caller blocks the JavaFX application thread.
 */
public final class HttpPdvBackendGateway implements PdvBackendGateway, AutoCloseable {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String JSON_MEDIA_TYPE = "application/json";

    /** Pairing is single use: one attempt only, because the backend may have succeeded anyway. */
    private static final int SINGLE_ATTEMPT = 1;

    /** Idempotent reads may be tried once more. */
    private static final int ONE_RETRY = 2;

    /**
     * Statuses that describe an upstream hiccup rather than a decision about the request. A plain 500 is
     * excluded on purpose: it may well be deterministic.
     */
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(502, 503, 504);

    private final PdvApiBaseUrl baseUrl;
    private final PdvApiSettings settings;
    private final Supplier<String> requestIdGenerator;
    private final HttpClient httpClient;
    private final PdvResponseMapper responseMapper;
    private final PdvErrorMapper errorMapper;
    private final PdvApiCallLogger logger = new PdvApiCallLogger();
    private final ObjectMapper objectMapper;

    /**
     * @param apiBaseUrl address taken from the phase 3 bootstrap configuration, never hardcoded
     */
    public HttpPdvBackendGateway(URI apiBaseUrl) {
        this(apiBaseUrl, PdvApiSettings.defaults());
    }

    public HttpPdvBackendGateway(URI apiBaseUrl, PdvApiSettings settings) {
        this(apiBaseUrl, settings, () -> UUID.randomUUID().toString());
    }

    /**
     * @param requestIdGenerator correlation id source; injectable so tests can assert propagation
     */
    HttpPdvBackendGateway(URI apiBaseUrl, PdvApiSettings settings, Supplier<String> requestIdGenerator) {
        this.baseUrl = PdvApiBaseUrl.of(Objects.requireNonNull(apiBaseUrl, "apiBaseUrl must not be null"));
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.requestIdGenerator = Objects.requireNonNull(requestIdGenerator, "requestIdGenerator must not be null");
        this.objectMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        this.responseMapper = new PdvResponseMapper(objectMapper);
        this.errorMapper = new PdvErrorMapper(objectMapper);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public CompletableFuture<BackendHealth> health() {
        return dispatch(
                "health",
                "GET",
                PdvApiEndpoints.HEALTH,
                requestId -> baseRequest(PdvApiEndpoints.HEALTH, requestId).GET().build(),
                responseMapper::health,
                ONE_RETRY
        );
    }

    @Override
    public CompletableFuture<PairedTerminal> pairTerminal(PairTerminalCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        String payload;
        try {
            payload = objectMapper.writeValueAsString(
                    new PairTerminalRequestDto(command.pairingCode(), command.installationId().toString())
            );
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            // Cannot happen for this payload shape; reported without echoing the pairing code.
            return CompletableFuture.failedFuture(
                    new PdvApiException(PdvApiFailure.of(PdvFailureKind.PROTOCOL_ERROR, null))
            );
        }

        return dispatch(
                "pair_terminal",
                "POST",
                PdvApiEndpoints.PAIR_TERMINAL,
                requestId -> baseRequest(PdvApiEndpoints.PAIR_TERMINAL, requestId)
                        .header("Content-Type", JSON_MEDIA_TYPE)
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        .build(),
                responseMapper::pairedTerminal,
                SINGLE_ATTEMPT
        );
    }

    @Override
    public CompletableFuture<CurrentTerminal> currentTerminal(MachineCredential credential) {
        Objects.requireNonNull(credential, "credential must not be null");

        return dispatch(
                "current_terminal",
                "GET",
                PdvApiEndpoints.CURRENT_TERMINAL,
                requestId -> baseRequest(PdvApiEndpoints.CURRENT_TERMINAL, requestId)
                        // The credential travels only here: never in the path, the query or the user agent.
                        .header("Authorization", credential.tokenType() + " " + credential.accessToken())
                        .GET()
                        .build(),
                responseMapper::currentTerminal,
                ONE_RETRY
        );
    }

    private HttpRequest.Builder baseRequest(String endpointPath, String requestId) {
        return HttpRequest.newBuilder(baseUrl.resolve(endpointPath))
                .timeout(settings.requestTimeout())
                .header("Accept", JSON_MEDIA_TYPE)
                .header("User-Agent", settings.userAgent())
                .header(REQUEST_ID_HEADER, requestId);
    }

    private <T> CompletableFuture<T> dispatch(
            String operation,
            String method,
            String path,
            Function<String, HttpRequest> requestFactory,
            BiFunction<String, String, T> bodyMapper,
            int maxAttempts
    ) {
        // One correlation id per logical operation, reused by a retry so support sees a single call.
        String requestId = requestIdGenerator.get();
        return attempt(operation, method, path, requestFactory, bodyMapper, maxAttempts, 1, requestId);
    }

    private <T> CompletableFuture<T> attempt(
            String operation,
            String method,
            String path,
            Function<String, HttpRequest> requestFactory,
            BiFunction<String, String, T> bodyMapper,
            int maxAttempts,
            int attemptNumber,
            String requestId
    ) {
        HttpRequest request = requestFactory.apply(requestId);
        long startedAt = System.nanoTime();

        return httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, throwable) -> settle(
                        operation,
                        method,
                        path,
                        requestFactory,
                        bodyMapper,
                        maxAttempts,
                        attemptNumber,
                        requestId,
                        response,
                        throwable,
                        Duration.ofNanos(System.nanoTime() - startedAt)
                ))
                .thenCompose(Function.identity());
    }

    private <T> CompletableFuture<T> settle(
            String operation,
            String method,
            String path,
            Function<String, HttpRequest> requestFactory,
            BiFunction<String, String, T> bodyMapper,
            int maxAttempts,
            int attemptNumber,
            String requestId,
            HttpResponse<String> response,
            Throwable throwable,
            Duration duration
    ) {
        if (throwable != null) {
            PdvApiFailure failure = errorMapper.fromTransport(throwable, requestId);
            if (failure.kind() == PdvFailureKind.TIMEOUT && attemptNumber < maxAttempts) {
                logger.retrying(operation, path, failure.kind(), attemptNumber + 1, requestId);
                return attempt(operation, method, path, requestFactory, bodyMapper,
                        maxAttempts, attemptNumber + 1, requestId);
            }
            logger.failed(operation, method, path, failure.kind(), duration, requestId);
            return CompletableFuture.failedFuture(new PdvApiException(failure, throwable));
        }

        int status = response.statusCode();
        Optional<String> headerRequestId = response.headers()
                .firstValue(REQUEST_ID_HEADER)
                .map(String::strip)
                .filter(value -> !value.isEmpty());
        String effectiveRequestId = headerRequestId.orElse(requestId);

        if (status >= 200 && status < 300) {
            logger.completed(operation, method, path, status, duration, effectiveRequestId);
            if (!isJson(response)) {
                // A proxy error page must never be parsed as a successful payload.
                return CompletableFuture.failedFuture(new PdvApiException(
                        PdvApiFailure.of(PdvFailureKind.INVALID_RESPONSE, effectiveRequestId)
                ));
            }
            try {
                return CompletableFuture.completedFuture(bodyMapper.apply(response.body(), effectiveRequestId));
            } catch (PdvApiException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }

        if (RETRYABLE_STATUSES.contains(status) && attemptNumber < maxAttempts) {
            logger.retrying(operation, path, PdvFailureKind.SERVER_ERROR, attemptNumber + 1, effectiveRequestId);
            return attempt(operation, method, path, requestFactory, bodyMapper,
                    maxAttempts, attemptNumber + 1, requestId);
        }

        PdvApiFailure failure = errorMapper.fromResponse(
                status,
                response.body(),
                response.headers().firstValue("Retry-After"),
                headerRequestId,
                requestId
        );
        logger.failed(operation, method, path, failure.kind(), duration, failure.requestId().orElse(effectiveRequestId));
        return CompletableFuture.failedFuture(new PdvApiException(failure));
    }

    private static boolean isJson(HttpResponse<String> response) {
        Optional<String> contentType = response.headers().firstValue("Content-Type");
        return contentType
                .map(value -> value.toLowerCase(Locale.ROOT).contains(JSON_MEDIA_TYPE))
                .orElse(false);
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
