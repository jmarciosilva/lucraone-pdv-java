package br.com.lucraone.pdv.infrastructure.api;

import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.TEST_REQUEST_TIMEOUT;
import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.failureOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.PairTerminalCommand;
import br.com.lucraone.pdv.application.api.PdvApiFailure;
import br.com.lucraone.pdv.application.api.PdvFailureKind;
import br.com.lucraone.pdv.infrastructure.api.PdvTestBackend.StubResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PdvApiErrorMappingTest {

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
    void mapsUnauthenticated() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.respondAlwaysWith(
                    StubResponse.json(401, PdvApiFixtures.error("unauthenticated", "Nao autenticado."))
            );

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                PdvApiFailure failure = failureOf(gateway.currentTerminal(credential()));

                assertEquals(PdvFailureKind.UNAUTHENTICATED, failure.kind());
                assertEquals("unauthenticated", failure.code().orElseThrow());
            }
        }
    }

    @Test
    void mapsForbidden() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.respondAlwaysWith(StubResponse.json(403, PdvApiFixtures.error("forbidden", "Sem permissao.")));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                PdvApiFailure failure = failureOf(gateway.currentTerminal(credential()));

                assertEquals(PdvFailureKind.FORBIDDEN, failure.kind());
                assertEquals("forbidden", failure.code().orElseThrow());
            }
        }
    }

    @Test
    void mapsPairingFailedFromTheStableCodeNotTheMessage() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(422, PdvApiFixtures.error("pairing_failed", "qualquer texto")));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                PdvApiFailure failure = failureOf(gateway.pairTerminal(command()));

                assertEquals(PdvFailureKind.PAIRING_FAILED, failure.kind());
                assertEquals("pairing_failed", failure.code().orElseThrow());
            }
        }
    }

    @Test
    void mapsValidationErrorIncludingFieldMessages() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(422, PdvApiFixtures.VALIDATION_ERROR));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                PdvApiFailure failure = failureOf(gateway.pairTerminal(command()));

                assertEquals(PdvFailureKind.VALIDATION, failure.kind());
                assertEquals("validation_error", failure.code().orElseThrow());
                assertEquals(
                        java.util.List.of("O codigo de pareamento e invalido."),
                        failure.fieldErrors().get("pairing_code")
                );
            }
        }
    }

    @Test
    void mapsRateLimitedAndExposesRetryAfterInSeconds() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.respondAlwaysWith(
                    StubResponse.json(429, PdvApiFixtures.error("rate_limited", "Muitas tentativas."))
                            .withHeader("Retry-After", "42")
            );

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                PdvApiFailure failure = failureOf(gateway.currentTerminal(credential()));

                assertEquals(PdvFailureKind.RATE_LIMITED, failure.kind());
                assertEquals(Duration.ofSeconds(42), failure.retryAfter().orElseThrow());
            }
        }
    }

    @Test
    void rateLimitedWithoutRetryAfterLeavesTheHintEmpty() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.respondAlwaysWith(StubResponse.json(429, PdvApiFixtures.error("rate_limited", "Devagar.")));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                PdvApiFailure failure = failureOf(gateway.currentTerminal(credential()));

                assertEquals(PdvFailureKind.RATE_LIMITED, failure.kind());
                assertTrue(failure.retryAfter().isEmpty());
            }
        }
    }

    @Test
    void mapsInternalError() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(500, PdvApiFixtures.error("internal_error", "Falha interna.")));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                PdvApiFailure failure = failureOf(gateway.pairTerminal(command()));

                assertEquals(PdvFailureKind.SERVER_ERROR, failure.kind());
            }
        }
    }

    @Test
    void mapsBrokenJsonToInvalidResponse() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, "{\"data\":{\"status\":"));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                assertEquals(PdvFailureKind.INVALID_RESPONSE, failureOf(gateway.health()).kind());
            }
        }
    }

    @Test
    void mapsAMissingRequiredFieldToInvalidResponse() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, """
                    {"data":{"status":"ok","api":"pdv"}}"""));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                assertEquals(PdvFailureKind.INVALID_RESPONSE, failureOf(gateway.health()).kind());
            }
        }
    }

    @Test
    void mapsAMissingCredentialOnPairingToInvalidResponse() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, """
                    {"data":{
                      "terminal":{"id":"trm_1","name":"Caixa 01","status":"ACTIVE","installation_id":"i"},
                      "tenant":{"id":"tnt_1","name":"T"},
                      "company":{"id":"cmp_1","trade_name":"C"},
                      "branch":{"id":"brc_1","name":"B","code":"001"}
                    }}"""));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                assertEquals(PdvFailureKind.INVALID_RESPONSE, failureOf(gateway.pairTerminal(command())).kind());
            }
        }
    }

    @Test
    void refusesToParseAProxyHtmlPageAsJson() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.of(200, "text/html", "<html><body>503 proxy</body></html>"));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                assertEquals(PdvFailureKind.INVALID_RESPONSE, failureOf(gateway.health()).kind());
            }
        }
    }

    @Test
    void mapsAnUnexpectedStatusToProtocolError() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(404, PdvApiFixtures.error("not_found", "Rota ausente.")));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                assertEquals(PdvFailureKind.PROTOCOL_ERROR, failureOf(gateway.health()).kind());
            }
        }
    }

    @Test
    void mapsAClosedPortToNetworkUnavailable() {
        try (HttpPdvBackendGateway gateway =
                     new HttpPdvBackendGateway(PdvTestBackend.unusedBaseUrl(), settings())) {
            assertEquals(PdvFailureKind.NETWORK_UNAVAILABLE, failureOf(gateway.health()).kind());
        }
    }

    @Test
    void mapsAStalledBackendToTimeout() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.respondAlwaysWith(
                    StubResponse.json(200, PdvApiFixtures.HEALTH_OK).withDelay(Duration.ofSeconds(3))
            );

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                assertEquals(PdvFailureKind.TIMEOUT, failureOf(gateway.health()).kind());
            }
        }
    }

    @Test
    void theFailureSummaryCarriesNoBodyAndNoSecret() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(422, PdvApiFixtures.error("pairing_failed", "detalhe interno")));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                PdvApiFailure failure = failureOf(gateway.pairTerminal(command()));

                assertTrue(failure.summary().contains("PAIRING_FAILED"));
                assertTrue(failure.summary().contains("pairing_failed"));
                assertTrue(failure.summary().contains("req_backend_1"));
                assertEquals(false, failure.summary().contains("detalhe interno"));
                assertEquals(false, failure.summary().contains(PdvApiFixtures.FAKE_PAIRING_CODE));
            }
        }
    }
}
