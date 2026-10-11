package br.com.lucraone.pdv.application.operator;

import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.OperatorCredential;
import br.com.lucraone.pdv.application.api.OperatorLoginCommand;
import br.com.lucraone.pdv.application.api.OperatorLoginResult;
import br.com.lucraone.pdv.application.api.OperatorSessionData;
import br.com.lucraone.pdv.application.api.PdvApiException;
import br.com.lucraone.pdv.application.api.PdvBackendGateway;
import br.com.lucraone.pdv.application.api.PdvFailureKind;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In-memory operator session manager. No password is ever stored. The credential is kept in a
 * volatile field and cleared on logout or session invalidation. Runs every operation on the
 * injected worker executor; never blocks the JavaFX application thread.
 *
 * <p>Periodic revalidation keeps the 240-minute sliding window alive. The scheduler fires every
 * {@value #REVALIDATION_INTERVAL_MINUTES} minutes on a daemon thread; it is cancelled on logout
 * or session invalidation. Close this service to shut down the scheduler.
 */
public final class OperatorSessionService implements AutoCloseable {

    private static final long REVALIDATION_INTERVAL_MINUTES = 30;

    private final PdvBackendGateway gateway;
    private final Clock clock;
    private final Executor executor;
    private final Supplier<MachineCredential> machineCredentialSupplier;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "operator-session-revalidator");
        t.setDaemon(true);
        return t;
    });

    private volatile OperatorCredential activeCredential;
    private volatile OperatorSessionData activeSession;
    private volatile MachineCredential loginMachineCredential;
    private volatile ScheduledFuture<?> revalidationTask;
    private final CopyOnWriteArrayList<Consumer<OperatorAuthSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong generation = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean revalidationInFlight = new java.util.concurrent.atomic.AtomicBoolean();

    public OperatorSessionService(PdvBackendGateway gateway, Clock clock, Executor executor) {
        this(gateway, clock, executor, () -> null);
    }

    public OperatorSessionService(PdvBackendGateway gateway, Clock clock, Executor executor,
            Supplier<MachineCredential> machineCredentialSupplier) {
        this.gateway = Objects.requireNonNull(gateway);
        this.clock = Objects.requireNonNull(clock);
        this.executor = Objects.requireNonNull(executor);
        this.machineCredentialSupplier = Objects.requireNonNull(machineCredentialSupplier);
    }

    public CompletableFuture<OperatorAuthSnapshot> login(MachineCredential machineCredential,
            OperatorLoginCommand command) {
        long loginGeneration = generation.incrementAndGet();
        return execute(() -> {
            OperatorLoginResult result;
            try {
                result = gateway.loginOperator(machineCredential, command).join();
            } catch (RuntimeException error) {
                return snapshotFromException(error);
            }
            OperatorCredential credential = result.credential();
            OperatorSessionData session = result.sessionData();
            if (generation.get() != loginGeneration) return OperatorAuthSnapshot.of(OperatorAuthState.NOT_AUTHENTICATED);
            if (credential.expiresAt().isBefore(clock.instant())) {
                return OperatorAuthSnapshot.of(OperatorAuthState.SESSION_INVALID);
            }
            activeCredential = credential;
            activeSession = session;
            loginMachineCredential = machineCredential;
            scheduleRevalidation(machineCredential);
            return new OperatorAuthSnapshot(OperatorAuthState.AUTHENTICATED, Optional.of(session),
                    Optional.of(result.requestId()));
        });
    }

    public CompletableFuture<OperatorAuthSnapshot> revalidate(MachineCredential machineCredential) {
        if (!revalidationInFlight.compareAndSet(false, true))
            return CompletableFuture.completedFuture(snapshot());
        return execute(() -> {
            long checkGeneration = generation.get();
            OperatorCredential credential = activeCredential;
            OperatorSessionData session = activeSession;
            if (credential == null || session == null) {
                return OperatorAuthSnapshot.of(OperatorAuthState.NOT_AUTHENTICATED);
            }
            if (credential.expiresAt().isBefore(clock.instant())) {
                generation.incrementAndGet();
                clearSession();
                return OperatorAuthSnapshot.of(OperatorAuthState.NOT_AUTHENTICATED);
            }
            MachineCredential currentMachineCredential = machineCredential;
            if (currentMachineCredential == null) {
                try { currentMachineCredential = machineCredentialSupplier.get(); }
                catch (RuntimeException unavailable) { return OperatorAuthSnapshot.of(OperatorAuthState.UNAVAILABLE); }
            }
            if (currentMachineCredential == null) return OperatorAuthSnapshot.of(OperatorAuthState.UNAVAILABLE);
            OperatorSessionData updated;
            try {
                updated = gateway.revalidateOperatorSession(currentMachineCredential, credential.accessToken()).join();
            } catch (RuntimeException error) {
                OperatorAuthSnapshot failed = snapshotFromException(error);
                if (failed.state() == OperatorAuthState.SESSION_INVALID || failed.state() == OperatorAuthState.ACCESS_DENIED
                        || failed.state() == OperatorAuthState.SESSION_CONFLICT) clearSession();
                return failed;
            }
            if (generation.get() != checkGeneration) return OperatorAuthSnapshot.of(OperatorAuthState.NOT_AUTHENTICATED);
            activeSession = updated;
            return new OperatorAuthSnapshot(OperatorAuthState.AUTHENTICATED, Optional.of(updated), Optional.empty());
        }).thenApply(snapshot -> { listeners.forEach(listener -> listener.accept(snapshot)); return snapshot; })
                .whenComplete((snapshot, error) -> revalidationInFlight.set(false));
    }

    public CompletableFuture<OperatorAuthSnapshot> logout(MachineCredential machineCredential) {
        OperatorCredential credential = activeCredential;
        generation.incrementAndGet();
        clearSession();
        return execute(() -> {
            if (credential == null) {
                return OperatorAuthSnapshot.of(OperatorAuthState.NOT_AUTHENTICATED);
            }
            try {
                gateway.logoutOperator(machineCredential, credential.accessToken()).join();
            } catch (RuntimeException ignored) {
                // Best-effort logout; session is already cleared locally.
            }
            return OperatorAuthSnapshot.of(OperatorAuthState.NOT_AUTHENTICATED);
        });
    }

    public OperatorAuthSnapshot snapshot() {
        OperatorCredential credential = activeCredential;
        OperatorSessionData session = activeSession;
        if (credential == null || session == null) {
            return OperatorAuthSnapshot.of(OperatorAuthState.NOT_AUTHENTICATED);
        }
        if (credential.expiresAt().isBefore(clock.instant())) {
            generation.incrementAndGet();
            clearSession();
            return OperatorAuthSnapshot.of(OperatorAuthState.NOT_AUTHENTICATED);
        }
        return new OperatorAuthSnapshot(OperatorAuthState.AUTHENTICATED, Optional.of(session), Optional.empty());
    }

    public void addListener(Consumer<OperatorAuthSnapshot> listener) { listeners.add(Objects.requireNonNull(listener)); }
    public void removeListener(Consumer<OperatorAuthSnapshot> listener) { listeners.remove(listener); }

    /** Immediately drops local operator authority without making a request with a rejected machine token. */
    public void invalidateLocal() {
        generation.incrementAndGet();
        clearSession();
        OperatorAuthSnapshot invalidated = OperatorAuthSnapshot.of(OperatorAuthState.SESSION_INVALID);
        listeners.forEach(listener -> listener.accept(invalidated));
    }

    @Override
    public void close() {
        clearSession();
        scheduler.shutdownNow();
    }

    private void clearSession() {
        activeCredential = null;
        activeSession = null;
        loginMachineCredential = null;
        cancelRevalidation();
    }

    private void scheduleRevalidation(MachineCredential machineCredential) {
        cancelRevalidation();
        revalidationTask = scheduler.scheduleAtFixedRate(
                () -> {
                    revalidate(null);
                },
                REVALIDATION_INTERVAL_MINUTES, REVALIDATION_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    private void cancelRevalidation() {
        ScheduledFuture<?> task = revalidationTask;
        revalidationTask = null;
        if (task != null) task.cancel(false);
    }

    private CompletableFuture<OperatorAuthSnapshot> execute(Supplier<OperatorAuthSnapshot> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.get();
            } catch (RuntimeException error) {
                return OperatorAuthSnapshot.of(OperatorAuthState.UNAVAILABLE);
            }
        }, executor);
    }

    private static OperatorAuthSnapshot snapshotFromException(RuntimeException error) {
        Throwable cause = error instanceof CompletionException ? error.getCause() : error;
        if (cause instanceof PdvApiException api) {
            PdvFailureKind kind = api.kind();
            if (kind == PdvFailureKind.OPERATOR_AUTH_FAILED) {
                return new OperatorAuthSnapshot(OperatorAuthState.LOGIN_FAILED, Optional.empty(),
                        api.failure().requestId());
            }
            if (kind == PdvFailureKind.OPERATOR_SESSION_INVALID) {
                return new OperatorAuthSnapshot(OperatorAuthState.SESSION_INVALID, Optional.empty(),
                        api.failure().requestId());
            }
            if (kind == PdvFailureKind.OPERATOR_SESSION_CONFLICT) {
                return new OperatorAuthSnapshot(OperatorAuthState.SESSION_CONFLICT, Optional.empty(),
                        api.failure().requestId());
            }
            OperatorAuthState state = switch (kind) {
                case NETWORK_UNAVAILABLE -> OperatorAuthState.NETWORK_UNAVAILABLE;
                case TIMEOUT -> OperatorAuthState.TIMEOUT;
                case INVALID_RESPONSE -> OperatorAuthState.INVALID_RESPONSE;
                case UNAUTHENTICATED, FORBIDDEN -> OperatorAuthState.ACCESS_DENIED;
                case RATE_LIMITED -> OperatorAuthState.RATE_LIMITED;
                case SERVER_ERROR -> OperatorAuthState.BACKEND_ERROR;
                case PROTOCOL_ERROR, VALIDATION, PAIRING_FAILED, ROTATION_CONFLICT, CREDENTIAL_RECOVERY_FAILED -> OperatorAuthState.PROTOCOL_ERROR;
                default -> OperatorAuthState.UNAVAILABLE;
            };
            return new OperatorAuthSnapshot(state, Optional.empty(), api.failure().requestId());
        }
        return OperatorAuthSnapshot.of(OperatorAuthState.UNAVAILABLE);
    }
}
