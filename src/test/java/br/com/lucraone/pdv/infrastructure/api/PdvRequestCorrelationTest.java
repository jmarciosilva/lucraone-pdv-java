package br.com.lucraone.pdv.infrastructure.api;

import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.TEST_REQUEST_TIMEOUT;
import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.failureOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.infrastructure.api.PdvTestBackend.StubResponse;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PdvRequestCorrelationTest {

    /** The backend accepts 1-128 ASCII characters limited to alphanumerics plus {@code . _ : -}. */
    private static final Pattern ACCEPTED_BY_BACKEND = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private static PdvApiSettings settings() {
        return PdvApiSettings.defaults().withRequestTimeout(TEST_REQUEST_TIMEOUT);
    }

    @Test
    void generatesAnIdentifierTheBackendAccepts() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.HEALTH_OK));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                gateway.health().join();
            }

            String sent = backend.singleRequest().header("X-Request-ID").orElseThrow();
            assertTrue(ACCEPTED_BY_BACKEND.matcher(sent).matches(), sent);
            assertEquals(sent, UUID.fromString(sent).toString(), "a UUID already satisfies the backend rule");
        }
    }

    @Test
    void keepsTheIdentifierWhenTheBackendEchoesIt() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            String fixed = "11111111-2222-4333-8444-999999999999";
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.HEALTH_OK).withHeader("X-Request-ID", fixed));

            try (HttpPdvBackendGateway gateway =
                         new HttpPdvBackendGateway(backend.baseUrl(), settings(), () -> fixed)) {
                assertEquals(fixed, gateway.health().join().requestId());
            }

            assertEquals(fixed, backend.singleRequest().header("X-Request-ID").orElseThrow());
        }
    }

    @Test
    void prefersTheBackendIdentifierWhenItSubstitutesOurs() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(
                    StubResponse.json(200, PdvApiFixtures.HEALTH_OK).withHeader("X-Request-ID", "req_backend_7")
            );

            try (HttpPdvBackendGateway gateway =
                         new HttpPdvBackendGateway(backend.baseUrl(), settings(), () -> "client-sent-1")) {
                assertEquals("req_backend_7", gateway.health().join().requestId());
            }

            assertEquals("client-sent-1", backend.singleRequest().header("X-Request-ID").orElseThrow());
        }
    }

    @Test
    void fallsBackToTheSentIdentifierWhenTheBackendOmitsIt() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.HEALTH_OK));

            try (HttpPdvBackendGateway gateway =
                         new HttpPdvBackendGateway(backend.baseUrl(), settings(), () -> "client-sent-2")) {
                assertEquals("client-sent-2", gateway.health().join().requestId());
            }
        }
    }

    @Test
    void failuresAlsoCarryACorrelationIdentifier() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.respondAlwaysWith(
                    StubResponse.json(401, PdvApiFixtures.error("unauthenticated", "nao autenticado"))
                            .withHeader("X-Request-ID", "req_backend_9")
            );

            try (HttpPdvBackendGateway gateway =
                         new HttpPdvBackendGateway(backend.baseUrl(), settings(), () -> "client-sent-3")) {
                assertEquals("req_backend_9", failureOf(gateway.health()).requestId().orElseThrow());
            }
        }
    }

    @Test
    void theResponseHeaderOutranksTheIdentifierInsideTheErrorBody() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            // The fixture body carries req_backend_1; the header must win.
            backend.respondAlwaysWith(
                    StubResponse.json(401, PdvApiFixtures.error("unauthenticated", "nao autenticado"))
                            .withHeader("X-Request-ID", "req_header_wins")
            );

            try (HttpPdvBackendGateway gateway =
                         new HttpPdvBackendGateway(backend.baseUrl(), settings(), () -> "client-sent-5")) {
                assertEquals("req_header_wins", failureOf(gateway.health()).requestId().orElseThrow());
            }
        }
    }

    @Test
    void fallsBackToTheErrorBodyIdentifierWhenNoHeaderIsSent() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.respondAlwaysWith(
                    StubResponse.json(401, PdvApiFixtures.error("unauthenticated", "nao autenticado"))
            );

            try (HttpPdvBackendGateway gateway =
                         new HttpPdvBackendGateway(backend.baseUrl(), settings(), () -> "client-sent-6")) {
                assertEquals("req_backend_1", failureOf(gateway.health()).requestId().orElseThrow());
            }
        }
    }

    @Test
    void fallsBackToTheSentIdentifierWhenNeitherHeaderNorBodyCarriesOne() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.respondAlwaysWith(StubResponse.json(401, "{\"error\":{\"code\":\"unauthenticated\"}}"));

            try (HttpPdvBackendGateway gateway =
                         new HttpPdvBackendGateway(backend.baseUrl(), settings(), () -> "client-sent-7")) {
                assertEquals("client-sent-7", failureOf(gateway.health()).requestId().orElseThrow());
            }
        }
    }

    @Test
    void retriesReuseTheSameIdentifierSoTheOperationStaysCorrelated() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(503, PdvApiFixtures.error("internal_error", "indisponivel")))
                    .enqueue(StubResponse.json(200, PdvApiFixtures.HEALTH_OK));

            try (HttpPdvBackendGateway gateway =
                         new HttpPdvBackendGateway(backend.baseUrl(), settings(), () -> "client-sent-4")) {
                gateway.health().join();
            }

            List<String> sent = backend.requests().stream()
                    .map(request -> request.header("X-Request-ID").orElseThrow())
                    .toList();
            assertEquals(List.of("client-sent-4", "client-sent-4"), sent);
        }
    }
}
