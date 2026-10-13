package br.com.lucraone.pdv.application.auth;

import static org.junit.jupiter.api.Assertions.*;

import br.com.lucraone.pdv.application.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;

/**
 * Homologation integration tests for the proactive credential renewal cycle.
 *
 * <p>Each test wires {@link CredentialRenewalService} → {@link CredentialRotationService} →
 * in-memory repositories + simulated HTTP gateway. No DPAPI, no real HTTP, no real clock.
 * Each credential, token and expiry is synthetic and confined to the test process.
 *
 * <h2>Scenários cobertos</h2>
 * <ol>
 *   <li>Terminal autenticado com credencial válida — C1, setup de todos os testes</li>
 *   <li>Agendamento correto da renovação — C1 (delay=0 dentro da janela de antecipação)</li>
 *   <li>Execução de prepare e confirm antes do vencimento — C1</li>
 *   <li>Persistência da nova credencial no repositório — C1, C5</li>
 *   <li>Atualização da credencial ativa em memória — C5 (repositório; UI: nota de limitação)</li>
 *   <li>Continuidade da sessão do operador — C6 (invariante estrutural)</li>
 *   <li>Funcionamento após o vencimento da credencial anterior — C1</li>
 *   <li>Ausência de suspensão indevida — nota de limitação (requer FX toolkit)</li>
 *   <li>Retentativas durante indisponibilidade do backend — C3</li>
 *   <li>Recuperação após restabelecimento da conexão — C3</li>
 * </ol>
 *
 * <h2>Limitação conhecida — cenários 5 (UI) e 8</h2>
 * A atualização de {@code activeMachineCredential} e a ausência de suspensão dependem de
 * {@code LucraOnePdvApplication}, uma aplicação JavaFX que não pode ser instanciada sem
 * toolkit gráfico. Esses cenários são garantidos pela correção D1: o {@code PauseTransition}
 * original em T dispara, chama {@code loadMachineCredentialAsync()} e atualiza
 * {@code activeMachineCredential} a partir do repositório que este teste verifica.
 */
class CredentialRenewalRotationIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /**
     * 30 min from NOW: within the 2-hour lead window → delay=0 (fires immediately).
     * 28 min before EXPIRY_FENCE (2 min) → not fenced.
     */
    private static final Instant EXPIRY_A = NOW.plusSeconds(1800);
    /** A second within-lead-window expiry distinct from EXPIRY_A (idempotency check). */
    private static final Instant EXPIRY_A2 = NOW.plusSeconds(1799);
    /** Operational expiry returned by the gateway for cycle-1 confirm. */
    private static final Instant EXPIRY_B = NOW.plusSeconds(43200);  // +12 h
    /** Operational expiry returned by the gateway for cycle-2 confirm. */
    private static final Instant EXPIRY_C = NOW.plusSeconds(86400);  // +24 h
    /** Inside the 2-minute fence → scheduleFor must silently skip. */
    private static final Instant EXPIRY_FENCED = NOW.plusSeconds(60);

    // ──────────────────────────────────────────────────────────────────────
    // C1. Cenários 1–4, 7: ciclo completo — prepare → candidata → confirm → promover → limpar
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void fullRotationCyclePersistsNewCredentialBeforeOriginalExpiry() throws Exception {
        var mem = new Mem();
        var gw  = new RotationGateway();
        try (var svc = buildRenewal(mem, gw)) {
            svc.scheduleFor(EXPIRY_A);
            assertTrue(gw.done.await(3, TimeUnit.SECONDS), "rotation must complete within timeout");
        }

        // Cenário 3: prepare and confirm were each called exactly once
        assertEquals(1, gw.prepareCalls.get(), "prepare must be called exactly once per cycle");
        assertEquals(1, gw.confirmCalls.get(), "confirm must be called exactly once per cycle");

        // Cenário 4: new credential persisted in the repository
        MachineCredential promoted = mem.active.credential();
        assertEquals("candidate-cycle-1", promoted.accessToken(),
                "persisted credential must carry the candidate token issued by the gateway");
        assertEquals(EXPIRY_B, promoted.expiresAt(),
                "persisted credential must carry the operational expiry from confirmRotation");

        // Cenário 7: new credential is valid after the original credential expires
        assertFalse(promoted.expiredAt(EXPIRY_A),
                "new credential must remain valid after the old credential's expiry instant");

        // Rotation marker must be cleared after success
        assertNull(mem.pendingRotation, "rotation marker must be deleted after successful rotation");
        assertNull(mem.candidate, "rotation candidate must be cleaned up after promotion");
        assertNull(mem.proof, "rotation proof must be cleaned up after promotion");
    }

    // ──────────────────────────────────────────────────────────────────────
    // C2. Múltiplos ciclos consecutivos: ciclo 2 com novo expiresAt dentro da janela
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void multipleCyclesRotateCorrectlyInSequence() throws Exception {
        var mem = new Mem();
        var gw  = new RotationGateway();
        try (var svc = buildRenewal(mem, gw)) {
            // Cycle 1
            svc.scheduleFor(EXPIRY_A);
            assertTrue(gw.done.await(3, TimeUnit.SECONDS), "cycle 1 must complete");

            assertEquals("candidate-cycle-1", mem.active.credential().accessToken());
            assertEquals(EXPIRY_B, mem.active.credential().expiresAt());

            // Simulate: monitor or PauseTransition calls scheduleFor with a new within-window expiry.
            // Use EXPIRY_A2 ≠ EXPIRY_A to avoid idempotent no-op.
            gw.done = new CountDownLatch(1);
            svc.scheduleFor(EXPIRY_A2);
            assertTrue(gw.done.await(3, TimeUnit.SECONDS), "cycle 2 must complete");
        }

        assertEquals(2, gw.prepareCalls.get(), "both cycles must call prepare once each");
        assertEquals(2, gw.confirmCalls.get(), "both cycles must call confirm once each");
        assertEquals("candidate-cycle-2", mem.active.credential().accessToken(),
                "cycle 2 must promote the second candidate token");
        assertEquals(EXPIRY_C, mem.active.credential().expiresAt(),
                "cycle 2 credential must carry the third operational expiry");
    }

    // ──────────────────────────────────────────────────────────────────────
    // C3 / Cenários 9–10: retentativas durante indisponibilidade + recuperação
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void transientBackendFailureTriggersRetriesUntilConnectionRestored() throws Exception {
        var mem = new Mem();
        var gw  = new RotationGateway();
        gw.prepareFailCount = 3; // first 3 prepare calls fail; 4th succeeds

        try (var svc = buildRenewal(mem, gw)) {
            svc.scheduleFor(EXPIRY_A);
            assertTrue(gw.done.await(3, TimeUnit.SECONDS),
                    "rotation must eventually succeed after transient backend failures");
        }

        // Cenário 9: at least 4 prepare calls (3 failures + 1 success)
        assertTrue(gw.prepareCalls.get() >= 4,
                "prepare must be retried at least 3 times before succeeding");
        // Cenário 10: rotation completes after reconnection
        assertEquals("candidate-cycle-1", mem.active.credential().accessToken(),
                "final credential must be the one from the successful cycle");
        assertEquals(EXPIRY_B, mem.active.credential().expiresAt());
        assertNull(mem.pendingRotation, "rotation marker must be cleared after recovery");
    }

    // ──────────────────────────────────────────────────────────────────────
    // C4 / Cenário 8: fence de expiração — sem rotação quando resta < 2 minutos
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void rotationIsSkippedWhenExpiryIsWithinTwoMinuteFence() throws Exception {
        var mem = new Mem();
        var gw  = new RotationGateway();
        try (var svc = buildRenewal(mem, gw)) {
            svc.scheduleFor(EXPIRY_FENCED); // NOW+60s < EXPIRY_FENCE (2min) → isPastFence = true
            Thread.sleep(100);
        }
        assertEquals(0, gw.prepareCalls.get(),
                "no HTTP calls must be made when within the 2-minute expiry fence");
        assertEquals("initial-token", mem.active.credential().accessToken(),
                "original credential must be unchanged when renewal is fenced out");
    }

    // ──────────────────────────────────────────────────────────────────────
    // C5. Cenário 5: nova credencial acessível para loadMachineCredentialAsync()
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void newCredentialInRepositoryIsReadableAfterRotation() throws Exception {
        // loadMachineCredentialAsync() calls authentication.loadStoredCredential() which reads from
        // the same repository. This test verifies the repository state after rotation, providing
        // evidence that activeMachineCredential would be correctly updated in the UI flow.
        var mem = new Mem();
        var gw  = new RotationGateway();
        try (var svc = buildRenewal(mem, gw)) {
            svc.scheduleFor(EXPIRY_A);
            assertTrue(gw.done.await(3, TimeUnit.SECONDS));
        }

        MachineCredential fromRepository = mem.active.credential();
        assertEquals("candidate-cycle-1", fromRepository.accessToken(),
                "repository must contain the promoted candidate token after rotation");
        // The new credential must be valid after the original credential's expiry instant
        assertFalse(fromRepository.expiredAt(EXPIRY_A),
                "credential from repository must be valid after the original credential expires");
    }

    // ──────────────────────────────────────────────────────────────────────
    // C6. Cenário 6: sessão do operador não é tocada — invariante estrutural
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void rotationServiceChainHasNoOperatorSessionDependency() {
        for (var cls : new Class<?>[]{ CredentialRenewalService.class, CredentialRotationService.class }) {
            for (var field : cls.getDeclaredFields()) {
                assertFalse(field.getType().getSimpleName().contains("Operator"),
                        cls.getSimpleName() + " must not hold an OperatorSessionService reference — "
                        + "found field: " + field.getName());
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // C7. Idempotência do scheduleFor — mesmo expiresAt não aciona rotação dupla
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void sameExpiryScheduledTwiceDoesNotDoubleRotate() throws Exception {
        var mem = new Mem();
        var gw  = new RotationGateway();
        try (var svc = buildRenewal(mem, gw)) {
            svc.scheduleFor(EXPIRY_A);
            svc.scheduleFor(EXPIRY_A); // idempotent — same value must not re-schedule
            assertTrue(gw.done.await(3, TimeUnit.SECONDS));
            Thread.sleep(60); // allow time for any hypothetical second fire
        }
        assertEquals(1, gw.prepareCalls.get(), "idempotent scheduleFor must not trigger double rotation");
        assertEquals(1, gw.confirmCalls.get());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Infrastructure: wire CredentialRenewalService → CredentialRotationService → in-memory repos
    // ──────────────────────────────────────────────────────────────────────

    private CredentialRenewalService buildRenewal(Mem mem, RotationGateway gw) {
        var rotationSvc = new CredentialRotationService(
                new ActiveRepo(mem), new CandidateRepo(mem),
                new RotationsRepo(mem), new ProofsRepo(mem), gw, CLOCK);

        Consumer<MachineCredential> rotateAndSignal = cred -> {
            rotationSvc.rotate(cred);   // throws on failure → CredentialRenewalService retries
            gw.done.countDown();         // reached only on success
        };

        return new CredentialRenewalService(
                rotateAndSignal,
                () -> mem.active.credential(),
                CLOCK,
                CredentialRenewalService.DEFAULT_LEAD_TIME,
                Runnable::run,
                Executors.newSingleThreadScheduledExecutor(),
                10L, 50L);
    }

    // ──────────────────────────────────────────────────────────────────────
    // In-memory durable state — replaces DPAPI + SQLite for test isolation
    // ──────────────────────────────────────────────────────────────────────

    static class Mem {
        StoredMachineCredential active = new StoredMachineCredential(
                UUID.randomUUID(),
                new MachineCredential("Bearer", "initial-token", EXPIRY_A));
        StoredMachineCredential candidate;
        CredentialRotationRepository.PendingRotation pendingRotation;
        CredentialRotationProofRepository.Proof proof;
    }

    record ActiveRepo(Mem m) implements MachineCredentialRepository {
        public void save(StoredMachineCredential v) { m.active = v; }
        public Optional<StoredMachineCredential> load() { return Optional.ofNullable(m.active); }
        public void delete() { m.active = null; }
        public void verifyWritable() { }
    }

    record CandidateRepo(Mem m) implements CandidateMachineCredentialRepository {
        public void save(MachineCredential v) {
            m.candidate = new StoredMachineCredential(UUID.randomUUID(), v);
        }
        public Optional<StoredMachineCredential> load() { return Optional.ofNullable(m.candidate); }
        public void delete() { m.candidate = null; }
    }

    record RotationsRepo(Mem m) implements CredentialRotationRepository {
        public void save(CredentialRotationRepository.PendingRotation v) { m.pendingRotation = v; }
        public Optional<CredentialRotationRepository.PendingRotation> load() {
            return Optional.ofNullable(m.pendingRotation);
        }
        public void delete() { m.pendingRotation = null; }
    }

    record ProofsRepo(Mem m) implements CredentialRotationProofRepository {
        public void save(CredentialRotationProofRepository.Proof v) { m.proof = v; }
        public Optional<CredentialRotationProofRepository.Proof> load() {
            return Optional.ofNullable(m.proof);
        }
        public void delete() { m.proof = null; }
    }

    /**
     * Simulated backend: each successful prepare issues a new candidate token;
     * the first {@code prepareFailCount} prepare calls simulate transient network failures.
     */
    static class RotationGateway extends AuthFixtures.Gateway {

        int prepareFailCount;
        final AtomicInteger prepareCalls = new AtomicInteger();
        final AtomicInteger confirmCalls = new AtomicInteger();
        volatile CountDownLatch done = new CountDownLatch(1);

        RotationGateway() { super(new AuthFixtures.Local()); }

        @Override
        public CompletableFuture<CredentialRotationResult> prepareRotation(
                MachineCredential credential, CredentialRotationCommand command) {
            int n = prepareCalls.incrementAndGet();
            if (n <= prepareFailCount) {
                return CompletableFuture.failedFuture(
                        new RuntimeException("simulated network failure on prepare attempt #" + n));
            }
            int cycle = confirmCalls.get() + 1; // which confirm this prepare belongs to
            MachineCredential candidate = new MachineCredential(
                    "Bearer", "candidate-cycle-" + cycle, EXPIRY_B);
            return CompletableFuture.completedFuture(
                    new CredentialRotationResult(command.operationId(), "PENDING",
                            NOW.plusSeconds(600), candidate, "req-prepare-" + cycle));
        }

        @Override
        public CompletableFuture<Instant> confirmRotation(MachineCredential candidate, String operationId) {
            int n = confirmCalls.incrementAndGet();
            // Cycle 1 → EXPIRY_B; cycle 2+ → EXPIRY_C
            return CompletableFuture.completedFuture(n == 1 ? EXPIRY_B : EXPIRY_C);
        }
    }
}
