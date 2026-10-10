package br.com.lucraone.pdv.infrastructure.api;

import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.TEST_REQUEST_TIMEOUT;
import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.failureOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.PairTerminalCommand;
import br.com.lucraone.pdv.application.api.PairedTerminal;
import br.com.lucraone.pdv.infrastructure.api.PdvTestBackend.StubResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the sanitised logging contract: the client records enough to support a call and nothing that
 * could leak a credential, a pairing code or a response body into a log file.
 */
class PdvApiSecretLoggingTest {

    private static final UUID INSTALLATION_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");

    private final List<String> captured = new CopyOnWriteArrayList<>();
    private Logger logger;
    private Handler handler;
    private Level previousLevel;

    @BeforeEach
    void captureApiLogging() {
        logger = Logger.getLogger(PdvApiCallLogger.LOGGER_NAME);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(String.valueOf(record.getMessage()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    @AfterEach
    void releaseApiLogging() {
        logger.removeHandler(handler);
        logger.setLevel(previousLevel);
    }

    private static PdvApiSettings settings() {
        return PdvApiSettings.defaults().withRequestTimeout(TEST_REQUEST_TIMEOUT);
    }

    private String allLogs() {
        return String.join("\n", captured);
    }

    @Test
    void neverLogsThePairingCode() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.PAIR_SUCCESS
                    .formatted(INSTALLATION_ID, PdvApiFixtures.FAKE_ACCESS_TOKEN)));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                gateway.pairTerminal(new PairTerminalCommand(PdvApiFixtures.FAKE_PAIRING_CODE, INSTALLATION_ID)).join();
            }

            assertFalse(captured.isEmpty(), "the call should have been logged at all");
            assertFalse(allLogs().contains(PdvApiFixtures.FAKE_PAIRING_CODE), allLogs());
        }
    }

    @Test
    void neverLogsTheAccessTokenReturnedByPairing() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.PAIR_SUCCESS
                    .formatted(INSTALLATION_ID, PdvApiFixtures.FAKE_ACCESS_TOKEN)));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                PairedTerminal paired = gateway.pairTerminal(
                        new PairTerminalCommand(PdvApiFixtures.FAKE_PAIRING_CODE, INSTALLATION_ID)).join();
                // Logging the result object itself must still be safe.
                Logger.getLogger(PdvApiCallLogger.LOGGER_NAME).info(paired.toString());
            }

            assertFalse(allLogs().contains(PdvApiFixtures.FAKE_ACCESS_TOKEN), allLogs());
        }
    }

    @Test
    void neverLogsTheAuthorizationHeaderOnAuthenticatedCalls() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.CURRENT_TERMINAL_SUCCESS.formatted(INSTALLATION_ID)));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                gateway.currentTerminal(new MachineCredential(
                        "Bearer",
                        PdvApiFixtures.FAKE_ACCESS_TOKEN,
                        java.time.Instant.parse("2026-12-31T23:59:59Z")
                )).join();
            }

            assertFalse(allLogs().contains(PdvApiFixtures.FAKE_ACCESS_TOKEN), allLogs());
            assertFalse(allLogs().toLowerCase(java.util.Locale.ROOT).contains("authorization"), allLogs());
        }
    }

    @Test
    void neverLogsTheResponseBodyOnFailure() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.respondAlwaysWith(StubResponse.json(500, PdvApiFixtures.error("internal_error", "segredo-no-corpo")));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                failureOf(gateway.health());
            }

            assertFalse(allLogs().contains("segredo-no-corpo"), allLogs());
        }
    }

    @Test
    void logsTheObservabilityFieldsNeededForSupport() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.HEALTH_OK).withHeader("X-Request-ID", "req_obs_1"));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                gateway.health().join();
            }

            String logs = allLogs();
            assertTrue(logs.contains("operation=health"), logs);
            assertTrue(logs.contains("method=GET"), logs);
            assertTrue(logs.contains("path=/api/v1/pdv/health"), logs);
            assertTrue(logs.contains("status=200"), logs);
            assertTrue(logs.contains("duration_ms="), logs);
            assertTrue(logs.contains("request_id=req_obs_1"), logs);
        }
    }

    @Test
    void theLogNeverCarriesTheBackendHostToAvoidLeakingUrlEmbeddedSecrets() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.HEALTH_OK));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                gateway.health().join();
            }

            assertFalse(allLogs().contains(backend.baseUrl().toString()), allLogs());
        }
    }
}
