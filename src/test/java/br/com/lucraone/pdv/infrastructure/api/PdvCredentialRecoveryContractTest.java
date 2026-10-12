package br.com.lucraone.pdv.infrastructure.api;

import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.TEST_REQUEST_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.infrastructure.api.PdvTestBackend.StubResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PdvCredentialRecoveryContractTest {
    @Test void recoveryUsesPublicOfficialEndpointThenConfirmsCandidateOnOfficialRoute() {
        String code = "ABC123.abcdef123456";
        String installation = "11111111-2222-4333-8444-555555555555";
        String deliverySecret = "a".repeat(64);
        String candidate = "synthetic-recovery-candidate";
        String result = """
                {"data":{"rotation":{"id":"00000000-0000-4000-8000-000000000001","status":"PENDING","expires_at":"2026-10-11T12:05:00Z"},
                  "credential":{"token_type":"Bearer","access_token":"%s","expires_at":"2026-10-11T12:05:00Z"}}}
                """.formatted(candidate);
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, result));
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.CREDENTIAL_CONFIRM_SUCCESS
                    .formatted("00000000-0000-4000-8000-000000000001")));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(),
                    PdvApiSettings.defaults().withRequestTimeout(TEST_REQUEST_TIMEOUT))) {
                var recovered = gateway.recoverCredential(code, installation, deliverySecret).join();
                assertEquals(candidate, recovered.candidateCredential().accessToken());
                gateway.confirmRecoveredCredential(recovered.candidateCredential(), recovered.operationId()).join();
            }
            var recoverRequest = backend.requests().get(0);
            assertEquals("POST", recoverRequest.method());
            assertEquals("/api/v1/pdv/terminal/credential/recover", recoverRequest.path());
            assertFalse(recoverRequest.headers().containsKey("authorization"));
            assertTrue(recoverRequest.body().contains(code));
            assertTrue(recoverRequest.body().contains(installation));
            assertTrue(recoverRequest.body().contains(deliverySecret));
            var confirmRequest = backend.requests().get(1);
            assertEquals("/api/v1/pdv/terminal/credential/confirm", confirmRequest.path());
            assertEquals("Bearer " + candidate, confirmRequest.headers().get("authorization"));
            assertTrue(confirmRequest.body().contains("00000000-0000-4000-8000-000000000001"));
        }
    }
}
