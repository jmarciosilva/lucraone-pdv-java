package br.com.lucraone.pdv.infrastructure.api;

import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.TEST_REQUEST_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.application.api.BackendHealth;
import br.com.lucraone.pdv.infrastructure.api.PdvTestBackend.RecordedRequest;
import br.com.lucraone.pdv.infrastructure.api.PdvTestBackend.StubResponse;
import org.junit.jupiter.api.Test;

class PdvHealthContractTest {

    private static PdvApiSettings settings() {
        return PdvApiSettings.defaults().withRequestTimeout(TEST_REQUEST_TIMEOUT);
    }

    @Test
    void callsTheVersionedHealthPathWithGet() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.HEALTH_OK));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                gateway.health().join();
            }

            RecordedRequest request = backend.singleRequest();
            assertEquals("GET", request.method());
            assertEquals("/api/v1/pdv/health", request.path());
        }
    }

    @Test
    void parsesTheHealthEnvelope() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.HEALTH_OK));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                BackendHealth health = gateway.health().join();

                assertEquals("ok", health.status());
                assertEquals("pdv", health.api());
                assertEquals("v1", health.version());
                assertTrue(health.versionSupported());
            }
        }
    }

    @Test
    void reportsAnUnsupportedContractVersionWithoutFailing() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, """
                    {"data":{"status":"ok","api":"pdv","version":"v2"}}"""));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                BackendHealth health = gateway.health().join();

                assertEquals("v2", health.version());
                assertFalse(health.versionSupported());
            }
        }
    }

    @Test
    void asksForJsonAndIdentifiesTheApplication() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.HEALTH_OK));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                gateway.health().join();
            }

            RecordedRequest request = backend.singleRequest();
            assertEquals("application/json", request.header("Accept").orElseThrow());
            assertTrue(request.header("User-Agent").orElseThrow().startsWith("LucraOne-PDV/"));
        }
    }

    @Test
    void sendsACorrelationIdentifier() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.HEALTH_OK));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                gateway.health().join();
            }

            assertTrue(backend.singleRequest().header("X-Request-ID").isPresent());
        }
    }

    @Test
    void healthRequiresNoAuthorization() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.HEALTH_OK));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                gateway.health().join();
            }

            assertTrue(backend.singleRequest().header("Authorization").isEmpty());
        }
    }
}
