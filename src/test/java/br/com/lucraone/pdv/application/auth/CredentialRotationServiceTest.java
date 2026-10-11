package br.com.lucraone.pdv.application.auth;

import static org.junit.jupiter.api.Assertions.*;

import br.com.lucraone.pdv.application.api.CredentialRotationCommand;
import br.com.lucraone.pdv.application.api.CredentialRotationResult;
import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.PdvBackendGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CredentialRotationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    @Test
    void deliverySecretIs64LowercaseHexChars() {
        AtomicReference<CredentialRotationCommand> captured = new AtomicReference<>();
        PdvBackendGateway gateway = stubGateway(captured);
        CredentialRotationService service = service(gateway);
        MachineCredential current = credential(NOW.plusSeconds(3600));

        service.rotate(current);

        CredentialRotationCommand command = captured.get();
        assertNotNull(command, "prepareRotation must be called");
        String secret = command.deliverySecret();
        assertTrue(HEX64.matcher(secret).matches(),
                "deliverySecret must be 64 lowercase hex chars, got: " + secret);
    }

    @Test
    void deliverySecretDiffersFromOperationId() {
        AtomicReference<CredentialRotationCommand> captured = new AtomicReference<>();
        PdvBackendGateway gateway = stubGateway(captured);
        CredentialRotationService service = service(gateway);

        service.rotate(credential(NOW.plusSeconds(3600)));

        CredentialRotationCommand command = captured.get();
        assertNotEquals(command.operationId(), command.deliverySecret(),
                "deliverySecret must not equal operationId");
    }

    @Test
    void deliverySecretIsRandomBetweenCalls() {
        AtomicReference<CredentialRotationCommand> first = new AtomicReference<>();
        AtomicReference<CredentialRotationCommand> second = new AtomicReference<>();

        service(stubGateway(first)).rotate(credential(NOW.plusSeconds(3600)));
        service(stubGateway(second)).rotate(credential(NOW.plusSeconds(3600)));

        assertNotEquals(first.get().deliverySecret(), second.get().deliverySecret(),
                "deliverySecret must be random between calls");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static MachineCredential credential(Instant expiresAt) {
        return new MachineCredential("Bearer", "fake-token-" + UUID.randomUUID(), expiresAt);
    }

    private static CredentialRotationService service(PdvBackendGateway gateway) {
        return new CredentialRotationService(
                new StubCredentials(), new StubCandidateCredentials(),
                new StubRotations(), gateway, CLOCK);
    }

    private static PdvBackendGateway stubGateway(AtomicReference<CredentialRotationCommand> capture) {
        return new NoOpGateway() {
            @Override
            public CompletableFuture<CredentialRotationResult> prepareRotation(
                    MachineCredential credential, CredentialRotationCommand command) {
                capture.set(command);
                String opId = command.operationId();
                MachineCredential candidate = new MachineCredential("Bearer",
                        "candidate-" + UUID.randomUUID(), NOW.plusSeconds(7200));
                return CompletableFuture.completedFuture(
                        new CredentialRotationResult(opId, "pending", NOW.plusSeconds(600), candidate, "req-" + opId));
            }

            @Override
            public CompletableFuture<Instant> confirmRotation(MachineCredential credential, String operationId) {
                return CompletableFuture.completedFuture(NOW.plusSeconds(1));
            }
        };
    }

    // ── Minimal stubs ─────────────────────────────────────────────────────────

    private static final class StubCredentials implements MachineCredentialRepository {
        private StoredMachineCredential stored;
        @Override public void save(StoredMachineCredential v) { stored = v; }
        @Override public Optional<StoredMachineCredential> load() { return Optional.ofNullable(stored); }
        @Override public void delete() { stored = null; }
        @Override public void verifyWritable() {}
    }

    private static final class StubCandidateCredentials implements CandidateMachineCredentialRepository {
        private StoredMachineCredential stored;
        @Override public void save(MachineCredential v) { stored = new StoredMachineCredential(UUID.randomUUID(), v); }
        @Override public Optional<StoredMachineCredential> load() { return Optional.ofNullable(stored); }
        @Override public void delete() { stored = null; }
    }

    private static final class StubRotations implements CredentialRotationRepository {
        @Override public void save(PendingRotation r) {}
        @Override public Optional<PendingRotation> load() { return Optional.empty(); }
        @Override public void delete() {}
    }

    private abstract static class NoOpGateway implements PdvBackendGateway {
        @Override public CompletableFuture<br.com.lucraone.pdv.application.api.BackendHealth> health() { throw new AssertionError(); }
        @Override public CompletableFuture<br.com.lucraone.pdv.application.api.PairedTerminal> pairTerminal(br.com.lucraone.pdv.application.api.PairTerminalCommand c) { throw new AssertionError(); }
        @Override public CompletableFuture<br.com.lucraone.pdv.application.api.CurrentTerminal> currentTerminal(MachineCredential c) { throw new AssertionError(); }
        @Override public CompletableFuture<br.com.lucraone.pdv.application.api.OperatorListResult> listOperators(MachineCredential c) { throw new AssertionError(); }
        @Override public CompletableFuture<br.com.lucraone.pdv.application.api.OperatorLoginResult> loginOperator(MachineCredential c, br.com.lucraone.pdv.application.api.OperatorLoginCommand cmd) { throw new AssertionError(); }
        @Override public CompletableFuture<br.com.lucraone.pdv.application.api.OperatorSessionData> getOperatorSession(MachineCredential c, String t) { throw new AssertionError(); }
        @Override public CompletableFuture<br.com.lucraone.pdv.application.api.OperatorSessionData> revalidateOperatorSession(MachineCredential c, String t) { throw new AssertionError(); }
        @Override public CompletableFuture<Void> logoutOperator(MachineCredential c, String t) { throw new AssertionError(); }
        @Override public CompletableFuture<CredentialRotationResult> prepareRotation(MachineCredential c, CredentialRotationCommand cmd) { throw new AssertionError(); }
        @Override public CompletableFuture<Instant> confirmRotation(MachineCredential c, String id) { throw new AssertionError(); }
    }
}
