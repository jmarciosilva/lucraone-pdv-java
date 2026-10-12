package br.com.lucraone.pdv.infrastructure.api;

import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.TEST_REQUEST_TIMEOUT;
import static org.junit.jupiter.api.Assertions.*;
import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.infrastructure.api.PdvTestBackend.StubResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PdvTerminalHeartbeatContractTest {
    @Test void postsMachineCredentialWithoutOperatorOrTenantHeaders() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, "{\"data\":{\"status\":\"ok\"}}"));
            var settings = PdvApiSettings.defaults().withRequestTimeout(TEST_REQUEST_TIMEOUT);
            try (var gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings)) {
                gateway.heartbeat(new MachineCredential("Bearer", PdvApiFixtures.FAKE_ACCESS_TOKEN,
                        Instant.parse("2026-12-31T23:59:59Z"))).join();
            }
            var request = backend.singleRequest();
            assertEquals("POST", request.method());
            assertEquals("/api/v1/pdv/terminal/heartbeat", request.path());
            assertEquals("Bearer " + PdvApiFixtures.FAKE_ACCESS_TOKEN, request.header("Authorization").orElseThrow());
            assertTrue(request.header("X-PDV-Operator-Session").isEmpty());
            assertTrue(request.header("X-Tenant-ID").isEmpty());
            assertFalse(request.body().contains(PdvApiFixtures.FAKE_ACCESS_TOKEN));
        }
    }
}
