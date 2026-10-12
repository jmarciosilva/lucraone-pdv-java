package br.com.lucraone.pdv.application.auth;

import static org.junit.jupiter.api.Assertions.*;

import br.com.lucraone.pdv.application.api.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CredentialRotationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final Instant EXPIRES = NOW.plusSeconds(7200);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    @Test void deliveryProofIsCryptographicallyShapedAndRedacted() {
        var state = new Memory(); var gateway = new RotationGateway(); var fixture = new Fixture(state, gateway);
        fixture.service().rotate(fixture.old.credential());
        assertTrue(HEX64.matcher(gateway.commands.getFirst().deliverySecret()).matches());
        assertNotEquals(gateway.commands.getFirst().operationId(), gateway.commands.getFirst().deliverySecret());
        assertEquals("CredentialRotationProof[redacted]", new CredentialRotationProofRepository.Proof(
                gateway.commands.getFirst().operationId(), gateway.commands.getFirst().deliverySecret()).toString());
    }

    @Test void lostPrepareResponseReusesOperationAndDeliverySecretAfterServiceReconstruction() {
        var state = new Memory(); var gateway = new RotationGateway(); var fixture = new Fixture(state, gateway);
        gateway.loseFirstPrepareResponse = true;
        assertThrows(TerminalAuthException.class, () -> fixture.service().rotate(fixture.old.credential()));
        var first = gateway.commands.getFirst();
        fixture.service().recoverPending(fixture.old.credential());
        assertEquals(2, gateway.commands.size());
        assertEquals(first.operationId(), gateway.commands.get(1).operationId());
        assertEquals(first.deliverySecret(), gateway.commands.get(1).deliverySecret());
        assertEquals(gateway.serverCandidate.accessToken(), state.active.credential().accessToken());
        assertTrue(state.rotation == null);
    }

    @Test void restartBeforeFirstPrepareUsesThePersistedOperationAndProof() {
        var state = new Memory(); var gateway = new RotationGateway(); var fixture = new Fixture(state, gateway);
        String operationId = "11111111-2222-4333-8444-555555555555";
        String deliverySecret = "d".repeat(64);
        state.rotation = new CredentialRotationRepository.PendingRotation(operationId, NOW);
        state.proof = new CredentialRotationProofRepository.Proof(operationId, deliverySecret);

        fixture.service().recoverPending(fixture.old.credential());

        assertEquals(1, gateway.commands.size());
        assertEquals(operationId, gateway.commands.getFirst().operationId());
        assertEquals(deliverySecret, gateway.commands.getFirst().deliverySecret());
        assertEquals(gateway.serverCandidate.accessToken(), state.active.credential().accessToken());
    }

    @Test void candidatePersistedBeforeConfirmationIsResumedWithoutPreparingAgain() {
        var state = new Memory(); var gateway = new RotationGateway(); var fixture = new Fixture(state, gateway);
        gateway.failFirstConfirm = true;
        assertThrows(TerminalAuthException.class, () -> fixture.service().rotate(fixture.old.credential()));
        var expected = state.rotationCandidate.credential();
        fixture.service().recoverPending(fixture.old.credential());
        assertEquals(1, gateway.commands.size());
        assertEquals(expected.accessToken(), state.active.credential().accessToken());
        assertEquals(2, gateway.confirmCalls);
    }

    @Test void lostConfirmationResponseAndPromotionCrashAreRecoveredIdempotently() {
        var state = new Memory(); var gateway = new RotationGateway(); var fixture = new Fixture(state, gateway);
        gateway.commitThenLoseFirstConfirmResponse = true;
        assertThrows(TerminalAuthException.class, () -> fixture.service().rotate(fixture.old.credential()));
        state.failNextActiveSave = true;
        assertThrows(TerminalAuthException.class, () -> fixture.service().recoverPending(fixture.old.credential()));
        // Rebuilt service and repositories observe the same persisted SQLite/SecretStore state.
        fixture.service().recoverPending(fixture.old.credential());
        assertEquals(1, gateway.commands.size());
        assertEquals(3, gateway.confirmCalls);
        assertEquals(gateway.serverCandidate.accessToken(), state.active.credential().accessToken());
        assertTrue(state.rotation == null);
        assertNull(state.rotationCandidate);
        assertNull(state.proof);
    }

    @Test void alreadyPromotedCredentialCanFinishCleanupAfterRestart() {
        var state = new Memory(); var gateway = new RotationGateway(); var fixture = new Fixture(state, gateway);
        state.failRotationDelete = true;
        assertThrows(TerminalAuthException.class, () -> fixture.service().rotate(fixture.old.credential()));
        // Server confirm and active credential persistence already completed; the persisted candidate is replayed.
        state.failRotationDelete = false;
        fixture.service().recoverPending(state.active.credential());
        assertEquals(1, gateway.commands.size());
        assertEquals(gateway.serverCandidate.accessToken(), state.active.credential().accessToken());
        assertNull(state.rotation);
    }

    @Test void expiredCandidateIsNeverReplacedByAnUnrelatedOperation() {
        var state = new Memory(); var gateway = new RotationGateway(); var fixture = new Fixture(state, gateway);
        gateway.expireCandidate = true;
        assertThrows(TerminalAuthException.class, () -> fixture.service().rotate(fixture.old.credential()));
        assertThrows(TerminalAuthException.class, () -> fixture.service().rotate(fixture.old.credential()));
        assertEquals(1, gateway.commands.size());
        assertNotNull(state.rotation);
        assertNotNull(state.rotationCandidate);
        assertEquals(fixture.old.credential().accessToken(), state.active.credential().accessToken());
    }

    @Test void secretStoreFailureDoesNotStartRemoteOperationOrDeleteCurrentCredential() {
        var state = new Memory(); var gateway = new RotationGateway(); var fixture = new Fixture(state, gateway);
        state.failProofSave = true;
        assertThrows(CredentialStorageException.class, () -> fixture.service().rotate(fixture.old.credential()));
        assertTrue(gateway.commands.isEmpty());
        assertNull(state.rotation);
        assertEquals(fixture.old.credential().accessToken(), state.active.credential().accessToken());
    }

    @Test void recoveryWithCandidateDoesNotRequireTheOldCredential() {
        var state = new Memory(); var gateway = new RotationGateway(); var fixture = new Fixture(state, gateway);
        gateway.failFirstConfirm = true;
        assertThrows(TerminalAuthException.class, () -> fixture.service().rotate(fixture.old.credential()));
        fixture.service().recoverPending(null);
        assertEquals(1, gateway.commands.size());
        assertEquals(gateway.serverCandidate.accessToken(), state.active.credential().accessToken());
    }

    @Test void verifiedAdministrativeRecoveryCanSupersedeAnExpiredRotation() {
        var state = new Memory(); var gateway = new RotationGateway(); var fixture = new Fixture(state, gateway);
        gateway.failFirstConfirm = true;
        assertThrows(TerminalAuthException.class, () -> fixture.service().rotate(fixture.old.credential()));
        state.active = new StoredMachineCredential(UUID.randomUUID(),
                new MachineCredential("Bearer", "admin-recovery-token", EXPIRES));
        fixture.service().clearAfterVerifiedAdministrativeRecovery();
        assertNull(state.rotation);
        assertNull(state.rotationCandidate);
        assertNull(state.proof);
        assertEquals("admin-recovery-token", state.active.credential().accessToken());
    }

    @Test void concurrentCallersCannotPrepareOverlappingRotations() throws Exception {
        var state = new Memory(); var gateway = new RotationGateway(); var fixture = new Fixture(state, gateway);
        gateway.prepareEntered = new CountDownLatch(1); gateway.releasePrepare = new CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> fixture.service().rotate(fixture.old.credential()));
            assertTrue(gateway.prepareEntered.await(2, TimeUnit.SECONDS));
            var second = pool.submit(() -> fixture.service().rotate(fixture.old.credential()));
            gateway.releasePrepare.countDown();
            first.get(2, TimeUnit.SECONDS); second.get(2, TimeUnit.SECONDS);
            assertEquals(2, gateway.commands.size());
            assertNotEquals(gateway.commands.get(0).operationId(), gateway.commands.get(1).operationId());
            assertEquals(1, gateway.maximumConcurrentPrepares.get());
        } finally { gateway.releasePrepare.countDown(); pool.shutdownNow(); }
    }

    private static final class Fixture {
        final Memory state; final RotationGateway gateway;
        final StoredMachineCredential old = new StoredMachineCredential(UUID.randomUUID(),
                new MachineCredential("Bearer", "old-machine-token", EXPIRES));
        Fixture(Memory state, RotationGateway gateway) { this.state = state; this.gateway = gateway; state.active = old; }
        CredentialRotationService service() {
            return new CredentialRotationService(new Active(state), new Candidate(state), new Rotations(state),
                    new Proofs(state), gateway, CLOCK);
        }
    }

    /** Shared durable-state simulation; a new service is created for each restart. */
    private static final class Memory {
        StoredMachineCredential active, rotationCandidate;
        CredentialRotationRepository.PendingRotation rotation;
        CredentialRotationProofRepository.Proof proof;
        boolean failProofSave, failNextActiveSave, failRotationDelete;
    }
    private record Active(Memory state) implements MachineCredentialRepository {
        @Override public void save(StoredMachineCredential value) {
            if (state.failNextActiveSave) { state.failNextActiveSave = false; throw new CredentialStorageException(CredentialStorageException.Kind.WRITE_FAILED); }
            state.active = value;
        }
        @Override public Optional<StoredMachineCredential> load() { return Optional.ofNullable(state.active); }
        @Override public void delete() { state.active = null; }
        @Override public void verifyWritable() { }
    }
    private record Candidate(Memory state) implements CandidateMachineCredentialRepository {
        @Override public void save(MachineCredential value) { state.rotationCandidate = new StoredMachineCredential(UUID.randomUUID(), value); }
        @Override public Optional<StoredMachineCredential> load() { return Optional.ofNullable(state.rotationCandidate); }
        @Override public void delete() { state.rotationCandidate = null; }
    }
    private record Rotations(Memory state) implements CredentialRotationRepository {
        @Override public void save(CredentialRotationRepository.PendingRotation value) { state.rotation = value; }
        @Override public Optional<CredentialRotationRepository.PendingRotation> load() { return Optional.ofNullable(state.rotation); }
        @Override public void delete() { if (state.failRotationDelete) throw new IllegalStateException(); state.rotation = null; }
    }
    private record Proofs(Memory state) implements CredentialRotationProofRepository {
        @Override public void save(CredentialRotationProofRepository.Proof value) { if (state.failProofSave) throw new CredentialStorageException(CredentialStorageException.Kind.UNAVAILABLE); state.proof = value; }
        @Override public Optional<CredentialRotationProofRepository.Proof> load() { return Optional.ofNullable(state.proof); }
        @Override public void delete() { state.proof = null; }
    }

    private static final class RotationGateway extends AuthFixtures.Gateway {
        final java.util.List<CredentialRotationCommand> commands = new java.util.ArrayList<>();
        MachineCredential serverCandidate;
        int confirmCalls;
        boolean loseFirstPrepareResponse, failFirstConfirm, commitThenLoseFirstConfirmResponse, confirmed, expireCandidate;
        CountDownLatch prepareEntered, releasePrepare;
        final AtomicInteger inFlightPrepares = new AtomicInteger(), maximumConcurrentPrepares = new AtomicInteger();
        RotationGateway() { super(new AuthFixtures.Local()); }
        @Override public CompletableFuture<CredentialRotationResult> prepareRotation(MachineCredential current, CredentialRotationCommand command) {
            commands.add(command);
            int inFlight = inFlightPrepares.incrementAndGet(); maximumConcurrentPrepares.accumulateAndGet(inFlight, Math::max);
            if (prepareEntered != null) {
                prepareEntered.countDown();
                try { if (!releasePrepare.await(2, TimeUnit.SECONDS)) return CompletableFuture.failedFuture(new RuntimeException("test timeout")); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); return CompletableFuture.failedFuture(error); }
            }
            if (serverCandidate == null) serverCandidate = new MachineCredential("Bearer", "candidate-token", EXPIRES);
            inFlightPrepares.decrementAndGet();
            if (loseFirstPrepareResponse && commands.size() == 1) return CompletableFuture.failedFuture(new RuntimeException("lost response"));
            return CompletableFuture.completedFuture(new CredentialRotationResult(command.operationId(), "PENDING", NOW.plusSeconds(600), serverCandidate, "request-prepare"));
        }
        @Override public CompletableFuture<Instant> confirmRotation(MachineCredential candidate, String operationId) {
            confirmCalls++;
            if (expireCandidate) return CompletableFuture.failedFuture(new PdvApiException(PdvApiFailure.of(PdvFailureKind.UNAUTHENTICATED, "request-expired")));
            if (commitThenLoseFirstConfirmResponse && confirmCalls == 1) { confirmed = true; return CompletableFuture.failedFuture(new RuntimeException("lost response")); }
            if (failFirstConfirm && confirmCalls == 1) return CompletableFuture.failedFuture(new RuntimeException("network"));
            confirmed = true;
            return CompletableFuture.completedFuture(EXPIRES);
        }
    }
}
