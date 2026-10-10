package br.com.lucraone.pdv.infrastructure.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Controlled HTTP backend for the API tests, built on the JDK's own {@code com.sun.net.httpserver}
 * so the suite needs no extra dependency and no Internet access.
 *
 * <p>It binds to an ephemeral loopback port, records every request it receives and answers from a queue
 * of stubbed responses, which lets a test drive status, headers, body and delay.
 */
final class PdvTestBackend implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final Deque<StubResponse> responses = new ArrayDeque<>();
    private StubResponse fallback = StubResponse.json(200, "{}");

    private PdvTestBackend(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    static PdvTestBackend start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            // A pool, not the default sequential dispatcher: a delayed stub must not block the next request,
            // which is exactly what the retry-after-timeout tests need.
            ExecutorService executor = Executors.newCachedThreadPool();
            server.setExecutor(executor);
            PdvTestBackend backend = new PdvTestBackend(server, executor);
            server.createContext("/", backend::handle);
            server.start();
            return backend;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start the test backend.", exception);
        }
    }

    /** A loopback base URL; http is accepted for loopback without weakening the production rule. */
    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** A port with nothing listening on it, to exercise connection failures. */
    static URI unusedBaseUrl() {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not reserve a closed port.", exception);
        }
    }

    PdvTestBackend enqueue(StubResponse response) {
        synchronized (responses) {
            responses.addLast(response);
        }
        return this;
    }

    PdvTestBackend respondAlwaysWith(StubResponse response) {
        this.fallback = response;
        return this;
    }

    List<RecordedRequest> requests() {
        return Collections.unmodifiableList(requests);
    }

    RecordedRequest singleRequest() {
        if (requests.size() != 1) {
            throw new AssertionError("Expected exactly one request but received " + requests.size());
        }
        return requests.get(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            requests.add(RecordedRequest.of(exchange, body));

            StubResponse response;
            synchronized (responses) {
                response = responses.isEmpty() ? fallback : responses.removeFirst();
            }

            if (!response.delay().isZero()) {
                try {
                    Thread.sleep(response.delay().toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            response.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            if (response.contentType() != null) {
                exchange.getResponseHeaders().set("Content-Type", response.contentType());
            }

            byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(payload);
                }
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    /** One request as the backend saw it, with header names lowercased for stable assertions. */
    record RecordedRequest(String method, String path, Map<String, String> headers, String body) {

        private static RecordedRequest of(HttpExchange exchange, byte[] body) {
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    headers.put(name.toLowerCase(Locale.ROOT), values.get(0));
                }
            });
            return new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    Map.copyOf(headers),
                    new String(body, StandardCharsets.UTF_8)
            );
        }

        Optional<String> header(String name) {
            return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
        }
    }

    /** One stubbed answer. {@code contentType} of {@code null} sends no Content-Type at all. */
    record StubResponse(int status, String contentType, String body, Map<String, String> headers, Duration delay) {

        static StubResponse json(int status, String body) {
            return new StubResponse(status, "application/json", body, Map.of(), Duration.ZERO);
        }

        static StubResponse of(int status, String contentType, String body) {
            return new StubResponse(status, contentType, body, Map.of(), Duration.ZERO);
        }

        StubResponse withHeader(String name, String value) {
            Map<String, String> merged = new HashMap<>(headers);
            merged.put(name, value);
            return new StubResponse(status, contentType, body, Map.copyOf(merged), delay);
        }

        StubResponse withDelay(Duration delay) {
            return new StubResponse(status, contentType, body, headers, delay);
        }
    }
}
