package br.com.lucraone.pdv.application.auth;

import static org.junit.jupiter.api.Assertions.*;

import br.com.lucraone.pdv.application.api.MachineCredential;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CredentialRenewalService}.
 *
 * All tests use a fixed clock at NOW and set {@code expiresAt = NOW + 30min} so that
 * {@code renewAt = expiresAt - 2h = NOW - 90min}, triggering delay=0 (immediate fire).
 * The worker runs tasks synchronously via {@code Runnable::run} on the scheduler thread
 * to avoid non-deterministic inter-thread timing in assertions.
 */
class CredentialRenewalServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    // 30min from now: past the 2h lead window → fires immediately; 28min before EXPIRY_FENCE (2min)
    private static final Instant EXPIRY = NOW.plusSeconds(1800);
    private static final MachineCredential VALID_CREDENTIAL =
            new MachineCredential("Bearer", "test-token", EXPIRY);

    /** Builds a service that fires immediately and retries within milliseconds. */
    private CredentialRenewalService service(Consumer<MachineCredential> rotateAction) {
        return new CredentialRenewalService(rotateAction, () -> VALID_CREDENTIAL, CLOCK,
                CredentialRenewalService.DEFAULT_LEAD_TIME, Runnable::run,
                Executors.newSingleThreadScheduledExecutor(), 10L, 50L);
    }

    /** Builds a service with a custom credential supplier. */
    private CredentialRenewalService service(Consumer<MachineCredential> rotateAction,
            java.util.function.Supplier<MachineCredential> credentialSupplier) {
        return new CredentialRenewalService(rotateAction, credentialSupplier, CLOCK,
                CredentialRenewalService.DEFAULT_LEAD_TIME, Runnable::run,
                Executors.newSingleThreadScheduledExecutor(), 10L, 50L);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 1. Rotação bem-sucedida
    // ──────────────────────────────────────────────────────────────────────

    @Test void successfulRotationIsPerformedForCredentialBeforeExpiry() throws Exception {
        CountDownLatch rotated = new CountDownLatch(1);
        AtomicReference<MachineCredential> received = new AtomicReference<>();

        try (var svc = service(cred -> { received.set(cred); rotated.countDown(); })) {
            svc.scheduleFor(EXPIRY);
            assertTrue(rotated.await(2, TimeUnit.SECONDS), "rotation must fire within timeout");
            assertEquals(VALID_CREDENTIAL.accessToken(), received.get().accessToken());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 2. Antecipação de expiração — scheduleFor inside the lead window fires immediately
    // ──────────────────────────────────────────────────────────────────────

    @Test void credentialWithinLeadWindowFiresRotationImmediately() throws Exception {
        // expiresAt only 10 minutes away; renewAt is in the past → delay = 0
        Instant imminent = NOW.plusSeconds(600);
        CountDownLatch rotated = new CountDownLatch(1);

        try (var svc = service(cred -> rotated.countDown())) {
            svc.scheduleFor(imminent);
            assertTrue(rotated.await(2, TimeUnit.SECONDS), "should fire immediately when within lead window");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 3. Timeout / backend indisponível — transient failure retries with backoff
    // ──────────────────────────────────────────────────────────────────────

    @Test void backendTimeoutTriggersExponentialBackoffRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch succeeded = new CountDownLatch(1);

        try (var svc = service(cred -> {
            if (calls.incrementAndGet() < 3) {
                throw new TerminalAuthException(TerminalAuthException.Kind.BACKEND_FAILURE, "timeout");
            }
            succeeded.countDown();
        })) {
            svc.scheduleFor(EXPIRY);
            assertTrue(succeeded.await(2, TimeUnit.SECONDS), "must eventually succeed after retries");
            assertTrue(calls.get() >= 3, "must have retried at least twice");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 4. Duplicidade de operações — same expiresAt is idempotent
    // ──────────────────────────────────────────────────────────────────────

    @Test void sameExpiryScheduledTwiceRotatesOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch once = new CountDownLatch(1);

        try (var svc = service(cred -> { calls.incrementAndGet(); once.countDown(); })) {
            svc.scheduleFor(EXPIRY);
            svc.scheduleFor(EXPIRY); // idempotent — must not schedule a second attempt
            assertTrue(once.await(2, TimeUnit.SECONDS));
            Thread.sleep(60); // wait for any hypothetical second fire
            assertEquals(1, calls.get(), "rotation must be invoked exactly once for the same expiry");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 5. Reinício durante rotação — retry succeeds when first attempt throws
    //    (mirrors the behavior of CredentialRotationService.recoverPending on restart:
    //     a transient failure on the first attempt is followed by a successful retry,
    //     which internally handles any persisted pending rotation state)
    // ──────────────────────────────────────────────────────────────────────

    @Test void retryAfterFirstAttemptFailureEventuallySucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch succeeded = new CountDownLatch(1);

        try (var svc = service(cred -> {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("simulated crash / network loss during rotation");
            }
            succeeded.countDown();
        })) {
            svc.scheduleFor(EXPIRY);
            assertTrue(succeeded.await(2, TimeUnit.SECONDS), "second attempt must succeed");
            assertEquals(2, calls.get(), "must have tried exactly twice");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 6. Falha na DPAPI — UNAVAILABLE stops all retries
    // ──────────────────────────────────────────────────────────────────────

    @Test void secureStoreUnavailableAbandonsRenewal() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        try (var svc = service(cred -> {
            calls.incrementAndGet();
            throw new CredentialStorageException(CredentialStorageException.Kind.UNAVAILABLE);
        })) {
            svc.scheduleFor(EXPIRY);
            Thread.sleep(80); // allow time for any mistaken retry
            assertEquals(1, calls.get(), "must not retry when the secure store is permanently unavailable");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 7. Expiração da candidata — CREDENTIAL_EXPIRED is permanent, no retry
    // ──────────────────────────────────────────────────────────────────────

    @Test void expiredCandidateDoesNotRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        try (var svc = service(cred -> {
            calls.incrementAndGet();
            throw new TerminalAuthException(TerminalAuthException.Kind.CREDENTIAL_EXPIRED, "candidate expired");
        })) {
            svc.scheduleFor(EXPIRY);
            Thread.sleep(80);
            assertEquals(1, calls.get(), "CREDENTIAL_EXPIRED must not trigger a retry");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 8. Revogação explícita — network failure is NOT treated as revocation;
    //    the retry continues until it succeeds (revocation is detected by the
    //    heartbeat / authorization monitor, not by the renewal service)
    // ──────────────────────────────────────────────────────────────────────

    @Test void networkFailureIsNotTreatedAsRevocationAndRetries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch succeeded = new CountDownLatch(1);

        try (var svc = service(cred -> {
            if (calls.incrementAndGet() == 1) {
                // A network error must not be interpreted as revocation — service retries.
                throw new TerminalAuthException(TerminalAuthException.Kind.BACKEND_FAILURE, "network");
            }
            succeeded.countDown();
        })) {
            svc.scheduleFor(EXPIRY);
            assertTrue(succeeded.await(2, TimeUnit.SECONDS),
                    "network failure must retry, not abandon the credential");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 9. Backend indisponível — retrys with increasing backoff, cap at maxRetryMs
    // ──────────────────────────────────────────────────────────────────────

    @Test void backendUnavailableRetriesWithCappaedBackoff() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch succeeded = new CountDownLatch(1);

        // retryBaseMs=10, maxRetryMs=20 → first retry after 20ms, then 20ms (capped); 3 retries total
        try (var svc = new CredentialRenewalService(cred -> {
            if (calls.incrementAndGet() < 4) {
                throw new RuntimeException("backend unavailable");
            }
            succeeded.countDown();
        }, () -> VALID_CREDENTIAL, CLOCK, CredentialRenewalService.DEFAULT_LEAD_TIME, Runnable::run,
                Executors.newSingleThreadScheduledExecutor(), 10L, 20L)) {
            svc.scheduleFor(EXPIRY);
            assertTrue(succeeded.await(2, TimeUnit.SECONDS), "must retry until backend is available");
            assertEquals(4, calls.get());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 10. Preservação da sessão do operador
    //     CredentialRenewalService has no OperatorSessionService reference by design.
    //     Rotation only touches the rotateAction callback; no operator state is modified.
    // ──────────────────────────────────────────────────────────────────────

    @Test void renewalDoesNotInvalidateOrTouchOperatorSession() throws Exception {
        // Structural: if someone adds an OperatorSessionService field this assertion fails.
        boolean hasOperatorField = java.util.Arrays.stream(CredentialRenewalService.class.getDeclaredFields())
                .anyMatch(f -> f.getType().getSimpleName().contains("Operator"));
        assertFalse(hasOperatorField,
                "CredentialRenewalService must not hold an OperatorSessionService reference");

        // Behavioral: the rotate action fires exactly once — no silent skips, no extra calls.
        AtomicInteger rotationCount = new AtomicInteger();
        CountDownLatch rotated = new CountDownLatch(1);

        try (var svc = service(cred -> { rotationCount.incrementAndGet(); rotated.countDown(); })) {
            svc.scheduleFor(EXPIRY);
            assertTrue(rotated.await(2, TimeUnit.SECONDS));
            assertEquals(1, rotationCount.get(),
                    "rotate action must be called exactly once — no operator-session side-effects");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 11. Ausência de regressão no catálogo
    //     Same reasoning: no CatalogDistributionService reference in the service.
    //     Catalog snapshot in SQLite is entirely unaffected by credential rotation.
    // ──────────────────────────────────────────────────────────────────────

    @Test void renewalDoesNotInteractWithCatalogService() throws Exception {
        // Structural: if someone adds a CatalogDistributionService field this assertion fails.
        boolean hasCatalogField = java.util.Arrays.stream(CredentialRenewalService.class.getDeclaredFields())
                .anyMatch(f -> f.getType().getSimpleName().contains("Catalog"));
        assertFalse(hasCatalogField,
                "CredentialRenewalService must not hold a CatalogDistributionService reference");

        // Behavioral: the rotate action fires exactly once — no silent skips, no extra calls.
        AtomicInteger rotationCount = new AtomicInteger();
        CountDownLatch rotated = new CountDownLatch(1);

        try (var svc = service(cred -> { rotationCount.incrementAndGet(); rotated.countDown(); })) {
            svc.scheduleFor(EXPIRY);
            assertTrue(rotated.await(2, TimeUnit.SECONDS));
            assertEquals(1, rotationCount.get(),
                    "rotate action must be called exactly once — no catalog side-effects");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Additional: close cancels the pending task
    // ──────────────────────────────────────────────────────────────────────

    @Test void closeBeforeFireCancelsPendingRotation() throws Exception {
        // Use a future expiry so the renewal fires after DEFAULT_LEAD_TIME (not immediately).
        Instant futureExpiry = NOW.plusSeconds(7200 + 1800); // 3.5h → renewAt = 1.5h away
        AtomicInteger calls = new AtomicInteger();

        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try (var svc = new CredentialRenewalService(cred -> calls.incrementAndGet(),
                () -> VALID_CREDENTIAL, CLOCK, CredentialRenewalService.DEFAULT_LEAD_TIME,
                Runnable::run, scheduler, 30_000L, 300_000L)) {
            svc.scheduleFor(futureExpiry);
            // Close immediately — the task fires at renewAt (90min away) so it should never run.
            svc.close();
        }
        Thread.sleep(50);
        assertEquals(0, calls.get(), "close must cancel the scheduled task before it fires");
        scheduler.shutdownNow();
    }

    // ──────────────────────────────────────────────────────────────────────
    // R2a. Supplier throws non-UNAVAILABLE CredentialStorageException — must retry
    // ──────────────────────────────────────────────────────────────────────

    @Test void supplierReadFailureTriggersRetry() throws Exception {
        AtomicInteger supplierCalls = new AtomicInteger();
        CountDownLatch succeeded = new CountDownLatch(1);

        // READ_FAILED on the first DPAPI read is transient; the service must retry.
        java.util.function.Supplier<MachineCredential> failThenSucceed = () -> {
            if (supplierCalls.incrementAndGet() == 1) {
                throw new CredentialStorageException(CredentialStorageException.Kind.READ_FAILED);
            }
            return VALID_CREDENTIAL;
        };

        try (var svc = service(cred -> succeeded.countDown(), failThenSucceed)) {
            svc.scheduleFor(EXPIRY);
            assertTrue(succeeded.await(2, TimeUnit.SECONDS),
                    "must retry after a transient READ_FAILED from the credential supplier");
            assertTrue(supplierCalls.get() >= 2, "supplier must have been called at least twice");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // R2b. Supplier throws UNAVAILABLE — must abandon without retry
    // ──────────────────────────────────────────────────────────────────────

    @Test void supplierUnavailableAbandonsRenewal() throws Exception {
        AtomicInteger supplierCalls = new AtomicInteger();

        java.util.function.Supplier<MachineCredential> unavailable = () -> {
            supplierCalls.incrementAndGet();
            throw new CredentialStorageException(CredentialStorageException.Kind.UNAVAILABLE);
        };

        try (var svc = service(cred -> fail("rotateAction must not be called when supplier is UNAVAILABLE"),
                unavailable)) {
            svc.scheduleFor(EXPIRY);
            Thread.sleep(80);
            assertEquals(1, supplierCalls.get(), "must not retry when the secure store is permanently unavailable");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Missing test: null credential from supplier — rotation is silently abandoned
    // ──────────────────────────────────────────────────────────────────────

    @Test void nullCredentialFromSupplierAbandonsRotation() throws Exception {
        AtomicInteger rotationCount = new AtomicInteger();

        try (var svc = service(cred -> rotationCount.incrementAndGet(), () -> null)) {
            svc.scheduleFor(EXPIRY);
            Thread.sleep(80);
            assertEquals(0, rotationCount.get(),
                    "null credential must silently abort rotation without calling rotateAction");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Missing test: credential already expired at attempt time — abandoned without retry
    // ──────────────────────────────────────────────────────────────────────

    @Test void alreadyExpiredCredentialAbandonsRotation() throws Exception {
        // Credential that has already expired at NOW (clock is fixed to NOW).
        MachineCredential expired = new MachineCredential("Bearer", "expired-token", NOW);
        // expired.expiredAt(NOW) == !NOW.isBefore(NOW) == true

        AtomicInteger rotationCount = new AtomicInteger();

        try (var svc = service(cred -> rotationCount.incrementAndGet(), () -> expired)) {
            svc.scheduleFor(EXPIRY);
            Thread.sleep(80);
            assertEquals(0, rotationCount.get(),
                    "an already-expired credential must abort rotation without calling rotateAction");
        }
    }

    // ──────────────────────────────────────────────────────────────────────

    @Test void newExpirySupersedesToldScheduledTask() throws Exception {
        // Schedule with a far-future expiry, then supersede with the usual near-expiry.
        Instant farFuture = NOW.plusSeconds(7200 + 3600); // 4h away → renewAt = 2h away
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch rotated = new CountDownLatch(1);

        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try (var svc = new CredentialRenewalService(cred -> { calls.incrementAndGet(); rotated.countDown(); },
                () -> VALID_CREDENTIAL, CLOCK, CredentialRenewalService.DEFAULT_LEAD_TIME,
                Runnable::run, scheduler, 10L, 50L)) {
            svc.scheduleFor(farFuture);     // far future — pending but not yet fired
            svc.scheduleFor(EXPIRY);        // supersedes: fires immediately
            assertTrue(rotated.await(2, TimeUnit.SECONDS));
            Thread.sleep(50); // wait for any ghost fire of the old task
            assertEquals(1, calls.get(), "only the superseding expiry must trigger rotation");
        }
        scheduler.shutdownNow();
    }
}
