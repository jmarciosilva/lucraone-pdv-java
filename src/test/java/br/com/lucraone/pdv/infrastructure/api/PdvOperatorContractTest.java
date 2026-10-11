package br.com.lucraone.pdv.infrastructure.api;

import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.TEST_REQUEST_TIMEOUT;
import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.failureOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.OperatorListResult;
import br.com.lucraone.pdv.application.api.OperatorLoginCommand;
import br.com.lucraone.pdv.application.api.OperatorLoginResult;
import br.com.lucraone.pdv.application.api.OperatorSessionData;
import br.com.lucraone.pdv.application.api.PdvFailureKind;
import br.com.lucraone.pdv.infrastructure.api.PdvTestBackend.StubResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PdvOperatorContractTest {

    private static final String FAKE_TOKEN = PdvApiFixtures.FAKE_ACCESS_TOKEN;
    private static final String FAKE_SESSION = PdvApiFixtures.FAKE_OPERATOR_SESSION_TOKEN;
    private static final String FAKE_OP_ID = PdvApiFixtures.FAKE_OPERATION_ID;

    private static MachineCredential credential() {
        return new MachineCredential("Bearer", FAKE_TOKEN, Instant.parse("2026-12-31T23:59:59Z"));
    }

    private static PdvApiSettings settings() {
        return PdvApiSettings.defaults().withRequestTimeout(TEST_REQUEST_TIMEOUT);
    }

    @Test
    void listOperatorsReturnsOperators() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.OPERATORS_SUCCESS));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                OperatorListResult result = gateway.listOperators(credential()).join();
                assertEquals("brc_1", result.branchId());
                assertEquals(2, result.operators().size());
                assertEquals("op_1", result.operators().get(0).id());
                assertEquals("João Caixa", result.operators().get(0).displayName());
            }
        }
    }

    @Test
    void listOperatorsSendsAuthorizationHeader() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.OPERATORS_SUCCESS));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                gateway.listOperators(credential()).join();
                var request = backend.requests().get(0);
                String auth = request.header("authorization").orElseThrow();
                assertTrue(auth.startsWith("Bearer "), "Must use Bearer scheme");
            }
        }
    }

    @Test
    void loginOperatorReturnsSession() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(201,
                    PdvApiFixtures.OPERATOR_LOGIN_SUCCESS.formatted(FAKE_SESSION)));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                OperatorLoginResult result = gateway.loginOperator(credential(),
                        new OperatorLoginCommand("joao.caixa", "secret")).join();
                assertNotNull(result.sessionData());
                assertEquals("ses_1", result.sessionData().id());
                assertEquals("João Caixa", result.sessionData().operatorName());
                assertEquals("joao.caixa", result.sessionData().operatorUsername());
                assertEquals("joao@example.com", result.sessionData().operatorEmail());
                assertNotNull(result.credential());
                assertEquals("PdvOperatorSession", result.credential().tokenType());
            }
        }
    }

    @Test
    void loginCanRecoverSessionWithHttp200AndOptionalEmail() {
        String body = PdvApiFixtures.OPERATOR_LOGIN_SUCCESS.formatted(FAKE_SESSION)
                .replace(",\"email\":\"joao@example.com\"", "")
                .replace("\"session\":{\"id\":\"ses_1\",\"status\":\"active\",\"expires_at\":\"2026-12-31T23:59:59Z\"}",
                        "\"session\":{\"id\":\"ses_1\",\"status\":\"active\",\"expires_at\":null}");
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, body));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                var result = gateway.loginOperator(credential(), new OperatorLoginCommand("joao.caixa", "secret")).join();
                assertEquals("joao.caixa", result.sessionData().operatorUsername());
                assertEquals(null, result.sessionData().operatorEmail());
                assertEquals(null, result.sessionData().expiresAt());
                assertEquals(FAKE_SESSION, result.credential().accessToken());
            }
        }
    }

    @Test
    void getAndRevalidateDoNotRequireCredentialRedelivery() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.OPERATOR_SESSION_GET_SUCCESS));
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.OPERATOR_REVALIDATE_SUCCESS));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                assertEquals("joao.caixa", gateway.getOperatorSession(credential(), FAKE_SESSION).join().operatorUsername());
                assertEquals("joao.caixa", gateway.revalidateOperatorSession(credential(), FAKE_SESSION).join().operatorUsername());
            }
        }
    }

    @Test
    void malformedSessionTimestampAndMissingOperatorAreRejected() {
        String badTimestamp = PdvApiFixtures.OPERATOR_SESSION_GET_SUCCESS.replace(
                "\"expires_at\":null", "\"expires_at\":\"not-a-timestamp\"");
        String missingOperator = PdvApiFixtures.OPERATOR_SESSION_GET_SUCCESS.replace(
                "\"operator\":{\"id\":\"op_1\",\"name\":\"João Caixa\",\"username\":\"joao.caixa\"},", "");
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, badTimestamp));
            backend.enqueue(StubResponse.json(200, missingOperator));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                assertEquals(PdvFailureKind.INVALID_RESPONSE, failureOf(gateway.getOperatorSession(credential(), FAKE_SESSION)).kind());
                assertEquals(PdvFailureKind.INVALID_RESPONSE, failureOf(gateway.getOperatorSession(credential(), FAKE_SESSION)).kind());
            }
        }
    }

    @Test
    void loginOperatorNeverRetries() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(503, "{}"));
            backend.enqueue(StubResponse.json(201,
                    PdvApiFixtures.OPERATOR_LOGIN_SUCCESS.formatted(FAKE_SESSION)));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                PdvFailureKind kind = failureOf(gateway.loginOperator(credential(),
                        new OperatorLoginCommand("joao.caixa", "secret"))).kind();
                assertEquals(PdvFailureKind.SERVER_ERROR, kind);
                assertEquals(1, backend.requests().size(), "Must not retry operator login");
            }
        }
    }

    @Test
    void operatorAuthFailedMapsCorrectly() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(422,
                    PdvApiFixtures.error("operator_authentication_failed", "Credenciais invalidas.")));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                PdvFailureKind kind = failureOf(gateway.loginOperator(credential(),
                        new OperatorLoginCommand("joao.caixa", "wrong"))).kind();
                assertEquals(PdvFailureKind.OPERATOR_AUTH_FAILED, kind);
            }
        }
    }

    @Test
    void operatorSessionConflictMapsCorrectly() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(409,
                    PdvApiFixtures.error("operator_session_conflict", "Sessao ativa.")));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                PdvFailureKind kind = failureOf(gateway.loginOperator(credential(),
                        new OperatorLoginCommand("joao.caixa", "secret"))).kind();
                assertEquals(PdvFailureKind.OPERATOR_SESSION_CONFLICT, kind);
            }
        }
    }

    @Test
    void getOperatorSessionSendsOperatorSessionHeader() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.OPERATOR_SESSION_GET_SUCCESS));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                OperatorSessionData session = gateway.getOperatorSession(credential(), FAKE_SESSION).join();
                assertEquals("ses_1", session.id());
                var request = backend.requests().get(0);
                assertTrue(request.header("x-pdv-operator-session").isPresent(),
                        "X-PDV-Operator-Session header must be present");
            }
        }
    }

    @Test
    void revalidateOperatorSessionSendsOperatorSessionHeader() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200,
                    PdvApiFixtures.OPERATOR_LOGIN_SUCCESS.formatted(FAKE_SESSION)));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                OperatorSessionData session = gateway.revalidateOperatorSession(credential(), FAKE_SESSION).join();
                assertNotNull(session);
                var request = backend.requests().get(0);
                assertTrue(request.header("x-pdv-operator-session").isPresent(),
                        "X-PDV-Operator-Session header must be present for revalidation");
            }
        }
    }

    @Test
    void logoutOperatorSendsDeleteRequest() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(204, ""));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                gateway.logoutOperator(credential(), FAKE_SESSION).join();
                var request = backend.requests().get(0);
                assertEquals("DELETE", request.method());
                assertTrue(request.header("x-pdv-operator-session").isPresent(),
                        "X-PDV-Operator-Session header must be present for logout");
            }
        }
    }
}
