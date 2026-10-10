package br.com.lucraone.pdv.infrastructure.api;

import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.TEST_REQUEST_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.application.api.PairTerminalCommand;
import br.com.lucraone.pdv.application.api.PairedTerminal;
import br.com.lucraone.pdv.infrastructure.api.PdvTestBackend.RecordedRequest;
import br.com.lucraone.pdv.infrastructure.api.PdvTestBackend.StubResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PdvPairingContractTest {

    private static final UUID INSTALLATION_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");

    private static PdvApiSettings settings() {
        return PdvApiSettings.defaults().withRequestTimeout(TEST_REQUEST_TIMEOUT);
    }

    private static PairTerminalCommand command() {
        return new PairTerminalCommand(PdvApiFixtures.FAKE_PAIRING_CODE, INSTALLATION_ID);
    }

    private static String successBody() {
        return PdvApiFixtures.PAIR_SUCCESS.formatted(INSTALLATION_ID, PdvApiFixtures.FAKE_ACCESS_TOKEN);
    }

    @Test
    void postsToTheVersionedPairingPath() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, successBody()));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                gateway.pairTerminal(command()).join();
            }

            RecordedRequest request = backend.singleRequest();
            assertEquals("POST", request.method());
            assertEquals("/api/v1/pdv/terminals/pair", request.path());
            assertEquals("application/json", request.header("Content-Type").orElseThrow());
        }
    }

    @Test
    void sendsExactlyThePairingCodeAndInstallationId() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, successBody()));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                gateway.pairTerminal(command()).join();
            }

            String body = backend.singleRequest().body();
            assertTrue(body.contains("\"pairing_code\":\"" + PdvApiFixtures.FAKE_PAIRING_CODE + "\""), body);
            assertTrue(body.contains("\"installation_id\":\"" + INSTALLATION_ID + "\""), body);
        }
    }

    @Test
    void pairingIsNotAuthenticated() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, successBody()));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                gateway.pairTerminal(command()).join();
            }

            assertTrue(backend.singleRequest().header("Authorization").isEmpty());
        }
    }

    @Test
    void mapsTheWholeCommercialContext() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, successBody()));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                PairedTerminal paired = gateway.pairTerminal(command()).join();

                assertEquals("trm_1", paired.context().terminal().id());
                assertEquals("Caixa 01", paired.context().terminal().name());
                assertEquals("ACTIVE", paired.context().terminal().status());
                assertEquals(INSTALLATION_ID.toString(), paired.context().terminal().installationId());
                assertEquals("tnt_1", paired.context().tenant().id());
                assertEquals("Rede Exemplo", paired.context().tenant().name());
                assertEquals("cmp_1", paired.context().company().id());
                assertEquals("Loja Exemplo", paired.context().company().tradeName());
                assertEquals("brc_1", paired.context().branch().id());
                assertEquals("Matriz", paired.context().branch().name());
                assertEquals("001", paired.context().branch().code());
            }
        }
    }

    @Test
    void mapsTheMachineCredentialIncludingExpiry() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, successBody()));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                PairedTerminal paired = gateway.pairTerminal(command()).join();

                assertEquals("Bearer", paired.credential().tokenType());
                assertEquals(PdvApiFixtures.FAKE_ACCESS_TOKEN, paired.credential().accessToken());
                assertEquals(Instant.parse("2026-12-31T23:59:59Z"), paired.credential().expiresAt());
            }
        }
    }

    @Test
    void neitherTheCommandNorTheResultExposeSecretsThroughToString() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, successBody()));

            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                PairTerminalCommand command = command();
                PairedTerminal paired = gateway.pairTerminal(command).join();

                assertFalse(command.toString().contains(PdvApiFixtures.FAKE_PAIRING_CODE), command.toString());
                assertFalse(paired.toString().contains(PdvApiFixtures.FAKE_ACCESS_TOKEN), paired.toString());
                assertFalse(
                        paired.credential().toString().contains(PdvApiFixtures.FAKE_ACCESS_TOKEN),
                        paired.credential().toString()
                );
            }
        }
    }
}
