package br.com.lucraone.pdv.application.auth;

import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.PdvApiException;
import br.com.lucraone.pdv.application.api.PdvBackendGateway;
import br.com.lucraone.pdv.application.api.PdvFailureKind;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Sends authenticated machine presence while terminal authorization is currently verified. */
public final class TerminalHeartbeatService implements AutoCloseable {
    private final PdvBackendGateway gateway;
    private final Supplier<MachineCredential> credentialSupplier;
    private final Runnable revoked;
    private final long intervalMillis;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile boolean authorized;
    private volatile boolean closed;
    private ScheduledFuture<?> scheduled;

    public TerminalHeartbeatService(PdvBackendGateway gateway, Supplier<MachineCredential> credentialSupplier,
            Runnable revoked, Duration interval) {
        this.gateway = Objects.requireNonNull(gateway);
        this.credentialSupplier = Objects.requireNonNull(credentialSupplier);
        this.revoked = Objects.requireNonNull(revoked);
        this.intervalMillis = Objects.requireNonNull(interval).toMillis();
        if (intervalMillis < 1) throw new IllegalArgumentException("interval must be positive");
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "terminal-heartbeat"); thread.setDaemon(true); return thread;
        });
    }

    /** Starts immediately; repeated activation does not create another schedule. */
    public synchronized void setAuthorized(boolean value) {
        if (closed || authorized == value) return;
        authorized = value;
        if (scheduled != null) scheduled.cancel(false);
        scheduled = null;
        if (value) scheduled = scheduler.scheduleWithFixedDelay(this::send, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void send() {
        if (closed || !authorized || !inFlight.compareAndSet(false, true)) return;
        try {
            MachineCredential credential = credentialSupplier.get();
            if (credential == null) { inFlight.set(false); return; }
            gateway.heartbeat(credential).whenComplete((ignored, error) -> {
                try {
                    if (!closed && authorized && isRevoked(error)) {
                        setAuthorized(false);
                        revoked.run();
                    }
                    // Network/server failures are transient; the fixed-delay schedule retries.
                } finally { inFlight.set(false); }
            });
        } catch (RuntimeException ignored) {
            // Secure-store and transport failures are retried on the next interval.
            inFlight.set(false);
        }
    }

    private static boolean isRevoked(Throwable error) {
        while (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) error = error.getCause();
        return error instanceof PdvApiException api
                && (api.kind() == PdvFailureKind.UNAUTHENTICATED || api.kind() == PdvFailureKind.FORBIDDEN);
    }

    @Override public synchronized void close() {
        closed = true; authorized = false;
        if (scheduled != null) scheduled.cancel(false);
        scheduler.shutdownNow();
    }
}
