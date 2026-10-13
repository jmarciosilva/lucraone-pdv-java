package br.com.lucraone.pdv.application.auth;

import br.com.lucraone.pdv.application.api.MachineCredential;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Proactively rotates the machine credential before expiry.
 *
 * <p>When {@link #scheduleFor} is called with the credential's expiry instant, a rotation is
 * scheduled {@link #DEFAULT_LEAD_TIME} before that instant, or immediately if already within the
 * window. On transient failure, retries with exponential backoff; gives up when fewer than 2
 * minutes remain before expiry — the normal restore path handles the expired state.
 *
 * <p>On success the new credential is persisted in the secure store. The existing expiry
 * {@code PauseTransition} and the authorization monitor pick it up automatically: no UI callback
 * is needed from this service.
 *
 * <p>Thread-safety: {@link #scheduleFor} and {@link #close} may be called from any thread.
 * The rotation itself is submitted to the injected {@code worker} executor.
 */
public final class CredentialRenewalService implements AutoCloseable {

    public static final Duration DEFAULT_LEAD_TIME = Duration.ofMinutes(120);
    private static final Duration EXPIRY_FENCE = Duration.ofMinutes(2);

    private final Consumer<MachineCredential> rotateAction;
    private final Supplier<MachineCredential> credentialSupplier;
    private final Clock clock;
    private final Duration leadTime;
    private final Executor worker;
    private final ScheduledExecutorService scheduler;
    /** Base unit of the exponential backoff. The first retry fires after {@code 2 × retryBaseMs}. */
    private final long retryBaseMs;
    private final long maxRetryMs;
    private final AtomicBoolean inFlight = new AtomicBoolean();

    private volatile Instant scheduledExpiry;
    private volatile ScheduledFuture<?> pendingTask;
    private volatile boolean closed;

    public CredentialRenewalService(CredentialRotationService rotationService,
            Supplier<MachineCredential> credentialSupplier,
            Clock clock, Duration leadTime, Executor worker) {
        this(rotationService::rotate, credentialSupplier, clock, leadTime, worker,
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "credential-renewal-scheduler");
                    t.setDaemon(true);
                    return t;
                }), 30_000L, 300_000L);
    }

    /**
     * Package-private for tests: allows injecting a custom scheduler and shorter retry intervals.
     *
     * @param retryBaseMs base unit of the exponential backoff; the first retry fires after
     *                    {@code 2 × retryBaseMs}, the second after {@code 4 × retryBaseMs}, etc.,
     *                    capped at {@code maxRetryMs}
     */
    CredentialRenewalService(Consumer<MachineCredential> rotateAction,
            Supplier<MachineCredential> credentialSupplier,
            Clock clock, Duration leadTime, Executor worker,
            ScheduledExecutorService scheduler, long retryBaseMs, long maxRetryMs) {
        this.rotateAction = Objects.requireNonNull(rotateAction);
        this.credentialSupplier = Objects.requireNonNull(credentialSupplier);
        this.clock = Objects.requireNonNull(clock);
        this.leadTime = Objects.requireNonNull(leadTime);
        this.worker = Objects.requireNonNull(worker);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.retryBaseMs = retryBaseMs;
        this.maxRetryMs = maxRetryMs;
    }

    /**
     * Announces the current credential's expiry. Schedules a proactive rotation
     * {@link #DEFAULT_LEAD_TIME} before that instant (or immediately if already in the window).
     * Idempotent for the same value; supersedes any pending schedule for a different value.
     * Safe to call from any thread.
     */
    public synchronized void scheduleFor(Instant expiresAt) {
        Objects.requireNonNull(expiresAt);
        if (closed) return;
        if (expiresAt.equals(scheduledExpiry)) return;
        scheduledExpiry = expiresAt;
        cancelPending();
        enqueueInitial(expiresAt);
    }

    private void enqueueInitial(Instant expiresAt) {
        Instant now = clock.instant();
        if (isPastFence(expiresAt, now)) return;
        Instant renewAt = expiresAt.minus(leadTime);
        long delayMs = Math.max(0L, Duration.between(now, renewAt).toMillis());
        pendingTask = scheduler.schedule(() -> attempt(expiresAt, retryBaseMs), delayMs, TimeUnit.MILLISECONDS);
    }

    private void enqueueRetry(Instant expiresAt, long prevRetryMs) {
        synchronized (this) {
            if (closed || !expiresAt.equals(scheduledExpiry)) return;
            if (isPastFence(expiresAt, clock.instant())) return;
            long nextMs = Math.min(prevRetryMs * 2, maxRetryMs);
            pendingTask = scheduler.schedule(() -> attempt(expiresAt, nextMs), nextMs, TimeUnit.MILLISECONDS);
        }
    }

    private void attempt(Instant expiresAt, long nextRetryMs) {
        if (closed || !expiresAt.equals(scheduledExpiry)) return;
        if (!inFlight.compareAndSet(false, true)) return;
        // Re-check after acquiring inFlight to guard against a concurrent scheduleFor.
        if (closed || !expiresAt.equals(scheduledExpiry)) {
            inFlight.set(false);
            return;
        }
        try {
            worker.execute(() -> rotate(expiresAt, nextRetryMs));
        } catch (RuntimeException ignored) {
            inFlight.set(false);
        }
    }

    private void rotate(Instant expiresAt, long currentRetryMs) {
        try {
            if (closed || !expiresAt.equals(scheduledExpiry)) return;
            MachineCredential credential = credentialSupplier.get();
            if (credential == null || credential.expiredAt(clock.instant())) return;
            rotateAction.accept(credential);
        } catch (TerminalAuthException error) {
            if (!isPermanentAuthError(error.kind())) enqueueRetry(expiresAt, currentRetryMs);
        } catch (CredentialStorageException error) {
            // UNAVAILABLE means the secure store is inaccessible on this machine; do not retry.
            if (error.kind() != CredentialStorageException.Kind.UNAVAILABLE) {
                enqueueRetry(expiresAt, currentRetryMs);
            }
        } catch (RuntimeException ignored) {
            enqueueRetry(expiresAt, currentRetryMs);
        } finally {
            inFlight.set(false);
        }
    }

    private boolean isPermanentAuthError(TerminalAuthException.Kind kind) {
        return kind == TerminalAuthException.Kind.CREDENTIAL_EXPIRED
                || kind == TerminalAuthException.Kind.CONTEXT_MISMATCH;
    }

    private boolean isPastFence(Instant expiresAt, Instant now) {
        return !expiresAt.isAfter(now.plus(EXPIRY_FENCE));
    }

    private synchronized void cancelPending() {
        if (pendingTask != null) {
            pendingTask.cancel(false);
            pendingTask = null;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        cancelPending();
        scheduler.shutdownNow();
    }
}
