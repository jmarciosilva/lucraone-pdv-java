package br.com.lucraone.pdv.infrastructure.api;

import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.TEST_REQUEST_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.application.api.CurrentTerminal;
import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.infrastructure.api.PdvTestBackend.RecordedRequest;
import br.com.lucraone.pdv.infrastructure.api.PdvTestBackend.StubResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PdvCurrentTerminalContractTest {

    private static final UUID INSTALLATION_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");

    private static PdvApiSettings settings() {
        return PdvApiSettings.defaults().withRequestTimeout(TEST_REQUEST_TIMEOUT);
    }

    private static MachineCredential credential() {
        return new MachineCredential(
                "Bearer",
                PdvApiFixtures.FAKE_ACCESS_TOKEN,
                Instant.parse("2026-12-31T23:59:59Z")
        );
    }

    private static String successBody() {
        return PdvApiFixtures.CURRENT_TERMINAL_SUCCESS.formatted(INSTALLATION_ID);
    }

    @Test
    void getsTheVersionedTerminalPath() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, successBody()));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                gateway.currentTerminal(credential()).join();
            }

            RecordedRequest request = backend.singleRequest();
            assertEquals("GET", request.method());
            assertEquals("/api/v1/pdv/terminal", request.path());
        }
    }

    @Test
    void sendsTheCredentialAsABearerAuthorizationHeader() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, successBody()));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                gateway.currentTerminal(credential()).join();
            }

            assertEquals(
                    "Bearer " + PdvApiFixtures.FAKE_ACCESS_TOKEN,
                    backend.singleRequest().header("Authorization").orElseThrow()
            );
        }
    }

    @Test
    void theTokenTravelsOnlyInTheAuthorizationHeader() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, successBody()));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                gateway.currentTerminal(credential()).join();
            }

            RecordedRequest request = backend.singleRequest();
            assertFalse(request.path().contains(PdvApiFixtures.FAKE_ACCESS_TOKEN));
            assertTrue(request.body().isEmpty());
            assertFalse(request.header("User-Agent").orElseThrow().contains(PdvApiFixtures.FAKE_ACCESS_TOKEN));
            assertFalse(request.header("X-Request-ID").orElseThrow().contains(PdvApiFixtures.FAKE_ACCESS_TOKEN));
        }
    }

    @Test
    void mapsTheContextAndTheCredentialExpiry() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, successBody()));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                CurrentTerminal terminal = gateway.currentTerminal(credential()).join();

                assertEquals("trm_1", terminal.context().terminal().id());
                assertEquals(INSTALLATION_ID.toString(), terminal.context().terminal().installationId());
                assertEquals("Rede Exemplo", terminal.context().tenant().name());
                assertEquals("Loja Exemplo", terminal.context().company().tradeName());
                assertEquals("001", terminal.context().branch().code());
                assertEquals(Instant.parse("2026-12-31T23:59:59Z"), terminal.credentialExpiresAt());
            }
        }
    }

    @Test
    void theResultCarriesNoAccessTokenBecauseTheBackendDoesNotReturnOne() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, successBody()));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                CurrentTerminal terminal = gateway.currentTerminal(credential()).join();

                assertFalse(terminal.toString().contains(PdvApiFixtures.FAKE_ACCESS_TOKEN), terminal.toString());
            }
        }
    }

    @Test
    void sendsACorrelationIdentifier() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, successBody()));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                gateway.currentTerminal(credential()).join();
            }

            assertTrue(backend.singleRequest().header("X-Request-ID").isPresent());
        }
    }
}
