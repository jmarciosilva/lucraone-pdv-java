package br.com.lucraone.pdv.infrastructure.api;

/**
 * Contract-shaped bodies for the API tests. Every value here is synthetic: no staging credential and no
 * real pairing code ever enters the suite.
 */
final class PdvApiFixtures {

    /** Synthetic pairing code, 19 characters like the backend format, but not a real one. */
    static final String FAKE_PAIRING_CODE = "TEST-FAKE-CODE-0001";

    /** Synthetic access token used only to assert header placement and redaction. */
    static final String FAKE_ACCESS_TOKEN = "fake-access-token-not-real-0001";

    static final String HEALTH_OK = """
            {"data":{"status":"ok","api":"pdv","version":"v1"}}""";

    static final String PAIR_SUCCESS = """
            {"data":{
              "terminal":{"id":"trm_1","name":"Caixa 01","status":"ACTIVE","installation_id":"%s"},
              "tenant":{"id":"tnt_1","name":"Rede Exemplo"},
              "company":{"id":"cmp_1","trade_name":"Loja Exemplo"},
              "branch":{"id":"brc_1","name":"Matriz","code":"001"},
              "credential":{"token_type":"Bearer","access_token":"%s","expires_at":"2026-12-31T23:59:59Z"}
            }}""";

    static final String CURRENT_TERMINAL_SUCCESS = """
            {"data":{
              "terminal":{"id":"trm_1","name":"Caixa 01","status":"ACTIVE","installation_id":"%s"},
              "tenant":{"id":"tnt_1","name":"Rede Exemplo"},
              "company":{"id":"cmp_1","trade_name":"Loja Exemplo"},
              "branch":{"id":"brc_1","name":"Matriz","code":"001"},
              "credential":{"expires_at":"2026-12-31T23:59:59Z"}
            }}""";

    static String error(String code, String message) {
        return """
                {"error":{"code":"%s","message":"%s","request_id":"req_backend_1"}}"""
                .formatted(code, message);
    }

    static final String VALIDATION_ERROR = """
            {"error":{"code":"validation_error","message":"Dados invalidos","request_id":"req_backend_1",
              "errors":{"pairing_code":["O codigo de pareamento e invalido."]}}}""";

    static final String FAKE_OPERATOR_SESSION_TOKEN = "fake-operator-session-token-0001";
    static final String FAKE_OPERATION_ID = "00000000-0000-4000-8000-000000000001";

    static final String OPERATORS_SUCCESS = """
            {"data":{
              "branch_id":"brc_1",
              "operators":[
                {"id":"op_1","display_name":"João Caixa","username":"joao.caixa"},
                {"id":"op_2","display_name":"Maria Caixa","username":"maria.caixa"}
              ]
            }}""";

    static final String OPERATOR_LOGIN_SUCCESS = """
            {"data":{
              "session":{"id":"ses_1","status":"active","expires_at":"2026-12-31T23:59:59Z"},
              "operator":{"id":"op_1","name":"João Caixa","username":"joao.caixa","email":"joao@example.com"},
              "context":{},"capabilities":{},"policy":{},"offline":{},
              "credential":{"token_type":"PdvOperatorSession","access_token":"%s","expires_at":"2026-12-31T23:59:59Z"}
            }}""";

    static final String OPERATOR_SESSION_GET_SUCCESS = """
            {"data":{
              "session":{"id":"ses_1","status":"active","expires_at":null},
              "operator":{"id":"op_1","name":"João Caixa","username":"joao.caixa"},
              "context":{},"capabilities":{},"policy":{},"offline":{}
            }}""";

    static final String OPERATOR_REVALIDATE_SUCCESS = """
            {"data":{"session":{"id":"ses_1","status":"active","expires_at":null},
              "operator":{"id":"op_1","name":"João Caixa","username":"joao.caixa"},
              "context":{},"capabilities":{},"policy":{},"offline":{}}}""";

    static final String CREDENTIAL_ROTATION_SUCCESS = """
            {"data":{
              "operation_id":"%s",
              "status":"pending",
              "rotation_expires_at":"2026-12-31T23:59:59Z",
              "credential":{"token_type":"Bearer","access_token":"%s","expires_at":"2027-12-31T23:59:59Z"}
            }}""";

    static final String CREDENTIAL_CONFIRM_SUCCESS = """
            {"data":{"confirmed_at":"2026-10-11T14:00:00Z"}}""";

    private PdvApiFixtures() {
    }
}
