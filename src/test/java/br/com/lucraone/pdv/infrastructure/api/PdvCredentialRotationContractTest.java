package br.com.lucraone.pdv.infrastructure.api;

import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.TEST_REQUEST_TIMEOUT;
import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.failureOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import br.com.lucraone.pdv.application.api.CredentialRotationCommand;
import br.com.lucraone.pdv.application.api.CredentialRotationResult;
import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.PdvFailureKind;
import br.com.lucraone.pdv.infrastructure.api.PdvTestBackend.StubResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PdvCredentialRotationContractTest {

    private static final String FAKE_TOKEN = PdvApiFixtures.FAKE_ACCESS_TOKEN;
    private static final String FAKE_NEW_TOKEN = "fake-new-access-token-not-real-0001";
    private static final String FAKE_OP_ID = PdvApiFixtures.FAKE_OPERATION_ID;

    private static MachineCredential credential() {
        return new MachineCredential("Bearer", FAKE_TOKEN, Instant.parse("2026-12-31T23:59:59Z"));
    }

    private static PdvApiSettings settings() {
        return PdvApiSettings.defaults().withRequestTimeout(TEST_REQUEST_TIMEOUT);
    }

    @Test
    void prepareRotationReturnsCandidateCredential() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(201,
                    PdvApiFixtures.CREDENTIAL_ROTATION_SUCCESS.formatted(FAKE_OP_ID, FAKE_NEW_TOKEN)));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                CredentialRotationResult result = gateway.prepareRotation(credential(),
                        new CredentialRotationCommand(FAKE_OP_ID, FAKE_OP_ID)).join();
                assertEquals(FAKE_OP_ID, result.operationId());
                assertEquals("pending", result.status());
                assertNotNull(result.candidateCredential());
                assertEquals("Bearer", result.candidateCredential().tokenType());
            }
        }
    }

    @Test
    void prepareRotationNeverRetries() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(503, "{}"));
            backend.enqueue(StubResponse.json(201,
                    PdvApiFixtures.CREDENTIAL_ROTATION_SUCCESS.formatted(FAKE_OP_ID, FAKE_NEW_TOKEN)));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                PdvFailureKind kind = failureOf(gateway.prepareRotation(credential(),
                        new CredentialRotationCommand(FAKE_OP_ID, FAKE_OP_ID))).kind();
                assertEquals(PdvFailureKind.SERVER_ERROR, kind);
                assertEquals(1, backend.requests().size(), "Must not retry rotation prepare");
            }
        }
    }

    @Test
    void rotationConflictMapsCorrectly() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(409,
                    PdvApiFixtures.error("credential_conflict", "Rotacao em andamento.")));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                PdvFailureKind kind = failureOf(gateway.prepareRotation(credential(),
                        new CredentialRotationCommand(FAKE_OP_ID, FAKE_OP_ID))).kind();
                assertEquals(PdvFailureKind.ROTATION_CONFLICT, kind);
            }
        }
    }

    @Test
    void confirmRotationReturnsConfirmedAt() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.CREDENTIAL_CONFIRM_SUCCESS));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                Instant confirmedAt = gateway.confirmRotation(credential(), FAKE_OP_ID).join();
                assertEquals(Instant.parse("2026-10-11T14:00:00Z"), confirmedAt);
            }
        }
    }

    @Test
    void confirmRotationUsesAuthorizationHeader() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.CREDENTIAL_CONFIRM_SUCCESS));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                gateway.confirmRotation(credential(), FAKE_OP_ID).join();
                var request = backend.requests().get(0);
                String auth = request.header("authorization").orElseThrow();
                assertEquals("Bearer " + FAKE_TOKEN, auth);
            }
        }
    }

    @Test
    void confirmRotationNeverRetries() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(503, "{}"));
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.CREDENTIAL_CONFIRM_SUCCESS));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                PdvFailureKind kind = failureOf(gateway.confirmRotation(credential(), FAKE_OP_ID)).kind();
                assertEquals(PdvFailureKind.SERVER_ERROR, kind);
                assertEquals(1, backend.requests().size(), "Must not retry confirm rotation");
            }
        }
    }
}
