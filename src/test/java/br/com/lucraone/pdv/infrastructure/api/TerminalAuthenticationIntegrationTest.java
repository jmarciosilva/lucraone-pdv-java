package br.com.lucraone.pdv.infrastructure.api;

import br.com.lucraone.pdv.application.auth.*;
import br.com.lucraone.pdv.application.security.SecretStore;
import br.com.lucraone.pdv.domain.terminal.Installation;
import br.com.lucraone.pdv.infrastructure.persistence.*;
import br.com.lucraone.pdv.infrastructure.platform.WindowsPlatformPaths;
import br.com.lucraone.pdv.infrastructure.security.SecretStoreMachineCredentialRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end local contract backend + real SQLite + protected-storage port. No Internet. */
class TerminalAuthenticationIntegrationTest {
    @TempDir Path temp;
    final Clock clock = Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC);
    final Map<String, byte[]> secrets = new HashMap<>();
    PdvTestBackend backend;
    HttpPdvBackendGateway gateway;
    ExecutorService worker;
    SqliteConnectionFactory connections;
    JdbcTerminalRepository terminals;
    JdbcTerminalValidationRepository validations;
    SecretStoreMachineCredentialRepository credentials;
    Installation installation;
    TerminalAuthenticationService auth;
    final String token = "lucraone-test-secret-" + UUID.randomUUID();

    @BeforeEach void setup() {
        backend = PdvTestBackend.start(); gateway = new HttpPdvBackendGateway(backend.baseUrl());
        worker = Executors.newSingleThreadExecutor();
        connections = new LocalDatabaseInitializer(new LocalDataDirectory(new WindowsPlatformPaths(key -> temp.toString()))).initialize();
        terminals = new JdbcTerminalRepository(new JdbcTransactionManager(connections));
        validations = new JdbcTerminalValidationRepository(new JdbcTransactionManager(connections));
        installation = terminals.ensureInstallation(new Installation(UUID.randomUUID(), clock.instant()));
        credentials = new SecretStoreMachineCredentialRepository(new SecretStore() {
            public void save(String key,byte[] value){byte[] previous=secrets.put(key,value.clone());if(previous!=null)Arrays.fill(previous,(byte)0);}
            public Optional<byte[]> load(String key){return Optional.ofNullable(secrets.get(key)).map(byte[]::clone);}
            public void delete(String key){var removed=secrets.remove(key);if(removed!=null)Arrays.fill(removed,(byte)0);}
        });
        auth = coordinator();
    }
    TerminalAuthenticationService coordinator() {
        return new TerminalAuthenticationService(terminals,credentials,validations,Optional.of(gateway),backend.baseUrl().toString(),clock,worker);
    }
    @AfterEach void close() {
        gateway.close(); worker.shutdownNow(); backend.close(); secrets.values().forEach(bytes -> Arrays.fill(bytes,(byte)0));
    }
    TerminalAuthSnapshot result(CompletableFuture<TerminalAuthSnapshot> future) throws Exception { return future.get(10,TimeUnit.SECONDS); }
    void pairResponse() { backend.enqueue(PdvTestBackend.StubResponse.json(200,PdvApiFixtures.PAIR_SUCCESS.formatted(installation.installationId(),token))); }
    void currentResponse() { backend.enqueue(PdvTestBackend.StubResponse.json(200,PdvApiFixtures.CURRENT_TERMINAL_SUCCESS.formatted(installation.installationId()))); }
    void authenticate() throws Exception { pairResponse();currentResponse();assertEquals(TerminalAuthState.AUTHENTICATED,result(auth.pair(PdvApiFixtures.FAKE_PAIRING_CODE)).state()); }

    @Test void pairingPersistsOnlySecretStoreAndRestoresAfterRestart() throws Exception {
        authenticate(); currentResponse();
        assertEquals(TerminalAuthState.AUTHENTICATED,result(coordinator().restore()).state());
        assertEquals(1,backend.requests().stream().filter(request -> request.method().equals("POST")).count());
        assertTrue(credentials.load().isPresent());assertTrue(validations.load().isPresent());
        assertEquals(installation.installationId(),terminals.findInstallation().orElseThrow().installationId());
        try(var c=connections.openConnection();var s=c.createStatement();var r=s.executeQuery("SELECT group_concat(sql) FROM sqlite_master")) {
            r.next();String schema=r.getString(1);assertFalse(schema.contains("access_token"));assertFalse(schema.contains("pairing_code"));
        }
        try(var c=connections.openConnection();var s=c.createStatement();var r=s.executeQuery("SELECT terminal_id || branch_id FROM terminal_provisioning")) {
            r.next();assertFalse(r.getString(1).contains(token));assertFalse(r.getString(1).contains(PdvApiFixtures.FAKE_PAIRING_CODE));
        }
    }
    @Test void offlineAfterRestartRequiresDurableMatchingProof() throws Exception {
        authenticate(); backend.close();
        assertEquals(TerminalAuthState.OFFLINE_LIMITED,result(coordinator().restore()).state());
        assertTrue(validations.load().isPresent());
    }
    @Test void unauthorizedCannotBeMaskedByPreviousProof() throws Exception {
        authenticate();backend.enqueue(PdvTestBackend.StubResponse.json(401,PdvApiFixtures.error("unauthenticated",token)));
        var rejected=result(coordinator().restore());
        assertEquals(TerminalAuthState.REAUTHENTICATION_REQUIRED,rejected.state());
        assertTrue(credentials.load().isEmpty());assertTrue(validations.load().isEmpty());
        assertEquals(Optional.of("req_backend_1"),rejected.requestId());assertFalse(rejected.toString().contains(token));
        backend.close();assertEquals(TerminalAuthState.PROVISIONED_NO_CREDENTIAL,result(coordinator().restore()).state());
    }
    @Test void corruptedDurableProofCannotAuthorizeOfflineOrStartAnOnlineRead() throws Exception {
        authenticate();
        long reads = backend.requests().size();
        try (var connection = connections.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE terminal_validation SET validated_at='synthetic-invalid-timestamp'");
        }
        backend.close();
        assertEquals(TerminalAuthState.LOCAL_STATE_UNAVAILABLE, result(coordinator().restore()).state());
        assertEquals(reads, backend.requests().size());
        assertTrue(credentials.load().isPresent());
    }
    @Test void failedPostIsNeverRetried() throws Exception {
        backend.respondAlwaysWith(PdvTestBackend.StubResponse.json(503,PdvApiFixtures.error("server_error","synthetic")));
        assertEquals(TerminalAuthState.VALIDATION_FAILED,result(auth.pair(PdvApiFixtures.FAKE_PAIRING_CODE)).state());
        assertEquals(1,backend.requests().size());assertTrue(credentials.load().isEmpty());
    }
    @Test void serverErrorCannotGrantOfflineOrRetainPriorProof() throws Exception {
        authenticate();backend.enqueue(PdvTestBackend.StubResponse.json(500,PdvApiFixtures.error("server_error","synthetic")));
        assertEquals(TerminalAuthState.VALIDATION_FAILED,result(coordinator().restore()).state());assertTrue(validations.load().isEmpty());
        backend.close();assertEquals(TerminalAuthState.CREDENTIAL_PRESENT_UNVERIFIED,result(coordinator().restore()).state());
    }
    @Test void lifecycleLogsAndSnapshotsNeverExposeSecrets() throws Exception {
        var logger=Logger.getLogger(PdvApiCallLogger.LOGGER_NAME);var messages=new CopyOnWriteArrayList<String>();
        Handler handler=new Handler() {
            public void publish(LogRecord record){messages.add(record.getMessage());}
            public void flush(){}public void close(){}
        };
        logger.addHandler(handler);
        try {
            authenticate();backend.enqueue(PdvTestBackend.StubResponse.json(403,PdvApiFixtures.error("forbidden",token+PdvApiFixtures.FAKE_PAIRING_CODE)));
            var snapshot=result(auth.restore());
            messages.add(snapshot.toString());messages.add(credentials.toString());
            assertFalse(messages.isEmpty());
            String output=String.join("\n",messages);
            assertFalse(output.contains(token));assertFalse(output.contains(PdvApiFixtures.FAKE_PAIRING_CODE));assertFalse(output.contains("Authorization"));
        } finally { logger.removeHandler(handler); }
    }
}
