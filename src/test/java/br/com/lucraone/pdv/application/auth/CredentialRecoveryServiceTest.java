package br.com.lucraone.pdv.application.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.application.api.CredentialRotationResult;
import br.com.lucraone.pdv.application.api.MachineCredential;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class CredentialRecoveryServiceTest {
    private static final String CODE = "ABC123.abcdef123456";

    @Test void persistsProofAndCandidateBeforeConfirmationThenValidatesWithoutChangingInstallIdentity() {
        AuthFixtures f = new AuthFixtures();
        f.provisioned();
        var originalInstallation = f.local.installation.installationId();
        Proofs proofs = new Proofs();
        Candidates candidates = new Candidates();
        RecoveryGateway gateway = new RecoveryGateway(f, proofs, candidates);
        CredentialRecoveryService service = new CredentialRecoveryService(f.credentials, candidates, proofs,
                gateway, f.auth(), AuthFixtures.CLOCK, Runnable::run);

        var result = service.recover(CODE).join();

        assertEquals(TerminalAuthState.AUTHENTICATED, result.state());
        assertEquals(originalInstallation, f.local.installation.installationId());
        assertEquals(1, gateway.recoverCalls);
        assertEquals(1, gateway.confirmCalls);
        assertTrue(gateway.proofWasDurableBeforeRequest);
        assertTrue(gateway.candidateWasDurableBeforeConfirm);
        assertNotNull(f.credentials.value);
        assertEquals("recovered-test-token", f.credentials.value.credential().accessToken());
        assertTrue(proofs.value.isEmpty());
        assertTrue(candidates.value.isEmpty());
    }

    @Test void leavesProofAndCandidateProtectedWhenConfirmationIsAmbiguous() {
        AuthFixtures f = new AuthFixtures(); f.provisioned();
        Proofs proofs = new Proofs(); Candidates candidates = new Candidates();
        RecoveryGateway gateway = new RecoveryGateway(f, proofs, candidates); gateway.failConfirm = true;
        CredentialRecoveryService service = new CredentialRecoveryService(f.credentials, candidates, proofs,
                gateway, f.auth(), AuthFixtures.CLOCK, Runnable::run);

        assertThrows(CompletionException.class, () -> service.recover(CODE).join());
        assertTrue(proofs.value.isPresent());
        assertTrue(candidates.value.isPresent());
        assertFalse(f.credentials.value == null && candidates.value.isEmpty());
    }

    @Test void doesNotReplaceAnExistingPendingProofWithAnotherCode() {
        AuthFixtures f = new AuthFixtures(); f.provisioned();
        Proofs proofs = new Proofs();
        proofs.value = Optional.of(new CredentialRecoveryRepository.Proof(CODE, f.local.installation.installationId(), "a".repeat(64)));
        Candidates candidates = new Candidates(); RecoveryGateway gateway = new RecoveryGateway(f, proofs, candidates);
        CredentialRecoveryService service = new CredentialRecoveryService(f.credentials, candidates, proofs,
                gateway, f.auth(), AuthFixtures.CLOCK, Runnable::run);
        assertThrows(CompletionException.class, () -> service.recover("XYZ789.abcdef123456").join());
        assertEquals(0, gateway.recoverCalls);
    }

    private static final class Proofs implements CredentialRecoveryRepository {
        Optional<Proof> value = Optional.empty();
        public void save(Proof proof) { value = Optional.of(proof); }
        public Optional<Proof> load() { return value; }
        public void delete() { value = Optional.empty(); }
    }

    private static final class Candidates implements CandidateMachineCredentialRepository {
        Optional<StoredMachineCredential> value = Optional.empty();
        public void save(MachineCredential c) { value = Optional.of(new StoredMachineCredential(UUID.randomUUID(), c)); }
        public Optional<StoredMachineCredential> load() { return value; }
        public void delete() { value = Optional.empty(); }
    }

    private static final class RecoveryGateway extends AuthFixtures.Gateway {
        private final Proofs proofs; private final Candidates candidates;
        int recoverCalls, confirmCalls; boolean proofWasDurableBeforeRequest, candidateWasDurableBeforeConfirm, failConfirm;
        RecoveryGateway(AuthFixtures f, Proofs proofs, Candidates candidates) { super(f.local); this.proofs=proofs; this.candidates=candidates; this.current=f.gateway.current; }
        @Override public java.util.concurrent.CompletableFuture<CredentialRotationResult> recoverCredential(String code, String installation, String secret) {
            recoverCalls++; proofWasDurableBeforeRequest = proofs.value.filter(p -> p.recoveryCode().equals(code)
                    && p.installationId().toString().equals(installation) && p.deliverySecret().equals(secret)).isPresent();
            return java.util.concurrent.CompletableFuture.completedFuture(new CredentialRotationResult("op-1", "PENDING",
                    AuthFixtures.NOW.plusSeconds(300), new MachineCredential("Bearer", "recovered-test-token", AuthFixtures.NOW.plusSeconds(3600)), "req-recover"));
        }
        @Override public java.util.concurrent.CompletableFuture<Instant> confirmRecoveredCredential(MachineCredential candidate, String operation) {
            confirmCalls++; candidateWasDurableBeforeConfirm = candidates.value.map(StoredMachineCredential::credential)
                    .filter(candidate::equals).isPresent();
            return failConfirm ? java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("synthetic"))
                    : java.util.concurrent.CompletableFuture.completedFuture(AuthFixtures.NOW.plusSeconds(3600));
        }
        @Override public java.util.concurrent.CompletableFuture<br.com.lucraone.pdv.application.api.CurrentTerminal> currentTerminal(MachineCredential credential) {
            return java.util.concurrent.CompletableFuture.completedFuture(new br.com.lucraone.pdv.application.api.CurrentTerminal(
                    AuthFixtures.context(local.installation.installationId(), "terminal-test", "branch-test"), credential.expiresAt(), "req-current"));
        }
    }
}
