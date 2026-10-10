package br.com.lucraone.pdv.infrastructure.api;

import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.TEST_REQUEST_TIMEOUT;
import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.failureOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.PairTerminalCommand;
import br.com.lucraone.pdv.application.api.PdvFailureKind;
import br.com.lucraone.pdv.infrastructure.api.PdvTestBackend.StubResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The policy is deliberately asymmetric: idempotent reads may be retried once, while pairing never is,
 * because the backend may have completed the pairing even when the answer was lost.
 */
class PdvRetryPolicyTest {

    private static final UUID INSTALLATION_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");

    private static PdvApiSettings settings() {
        return PdvApiSettings.defaults().withRequestTimeout(TEST_REQUEST_TIMEOUT);
    }

    private static PairTerminalCommand command() {
        return new PairTerminalCommand(PdvApiFixtures.FAKE_PAIRING_CODE, INSTALLATION_ID);
    }

    private static MachineCredential credential() {
        return new MachineCredential("Bearer", PdvApiFixtures.FAKE_ACCESS_TOKEN, Instant.parse("2026-12-31T23:59:59Z"));
    }

    @Test
    void healthRetriesOnceAfterServiceUnavailableAndThenSucceeds() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(503, PdvApiFixtures.error("internal_error", "indisponivel")))
                    .enqueue(StubResponse.json(200, PdvApiFixtures.HEALTH_OK));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                assertEquals("ok", gateway.health().join().status());
            }

            assertEquals(2, backend.requests().size());
        }
    }

    @Test
    void healthRetriesOnceAfterATimeoutAndThenSucceeds() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.HEALTH_OK).withDelay(Duration.ofSeconds(3)))
                    .enqueue(StubResponse.json(200, PdvApiFixtures.HEALTH_OK));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                assertEquals("ok", gateway.health().join().status());
            }

            assertEquals(2, backend.requests().size());
        }
    }

    @Test
    void healthGivesUpAfterASingleRetry() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.respondAlwaysWith(StubResponse.json(503, PdvApiFixtures.error("internal_error", "indisponivel")));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                assertEquals(PdvFailureKind.SERVER_ERROR, failureOf(gateway.health()).kind());
            }

            assertEquals(2, backend.requests().size());
        }
    }

    @Test
    void healthDoesNotRetryOnInternalServerError() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.respondAlwaysWith(StubResponse.json(500, PdvApiFixtures.error("internal_error", "falha")));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                assertEquals(PdvFailureKind.SERVER_ERROR, failureOf(gateway.health()).kind());
            }

            assertEquals(1, backend.requests().size());
        }
    }

    @Test
    void healthDoesNotRetryOnRateLimiting() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.respondAlwaysWith(StubResponse.json(429, PdvApiFixtures.error("rate_limited", "devagar")));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                assertEquals(PdvFailureKind.RATE_LIMITED, failureOf(gateway.health()).kind());
            }

            assertEquals(1, backend.requests().size());
        }
    }

    @Test
    void healthRetriesOnBadGatewayAndGatewayTimeout() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(502, PdvApiFixtures.error("internal_error", "gateway")))
                    .enqueue(StubResponse.json(200, PdvApiFixtures.HEALTH_OK));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                assertEquals("ok", gateway.health().join().status());
            }
            assertEquals(2, backend.requests().size());
        }

        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(504, PdvApiFixtures.error("internal_error", "gateway")))
                    .enqueue(StubResponse.json(200, PdvApiFixtures.HEALTH_OK));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                assertEquals("ok", gateway.health().join().status());
            }
            assertEquals(2, backend.requests().size());
        }
    }

    @Test
    void currentTerminalRetriesOnceAfterATransientFailure() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(503, PdvApiFixtures.error("internal_error", "indisponivel")))
                    .enqueue(StubResponse.json(200, PdvApiFixtures.CURRENT_TERMINAL_SUCCESS.formatted(INSTALLATION_ID)));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                assertEquals("trm_1", gateway.currentTerminal(credential()).join().context().terminal().id());
            }

            assertEquals(2, backend.requests().size());
        }
    }

    @Test
    void pairingIsNeverRetriedAfterATimeout() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.respondAlwaysWith(
                    StubResponse.json(200, PdvApiFixtures.PAIR_SUCCESS
                            .formatted(INSTALLATION_ID, PdvApiFixtures.FAKE_ACCESS_TOKEN))
                            .withDelay(Duration.ofSeconds(3))
            );

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                assertEquals(PdvFailureKind.TIMEOUT, failureOf(gateway.pairTerminal(command())).kind());
            }

            assertEquals(1, backend.requests().size(), "a single-use pairing code must never be replayed");
        }
    }

    @Test
    void pairingIsNeverRetriedOnServiceUnavailable() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.respondAlwaysWith(StubResponse.json(503, PdvApiFixtures.error("internal_error", "indisponivel")));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                assertEquals(PdvFailureKind.SERVER_ERROR, failureOf(gateway.pairTerminal(command())).kind());
            }

            assertEquals(1, backend.requests().size(), "a single-use pairing code must never be replayed");
        }
    }
}
