package br.com.lucraone.pdv.application.auth;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Periodically rechecks machine authorization without overlapping requests. */
public final class TerminalAuthorizationMonitor implements AutoCloseable {
    private final Supplier<CompletableFuture<TerminalAuthSnapshot>> check;
    private final Consumer<TerminalAuthSnapshot> listener;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean started;
    private ScheduledFuture<?> scheduledCheck;

    public TerminalAuthorizationMonitor(Supplier<CompletableFuture<TerminalAuthSnapshot>> check,
            Consumer<TerminalAuthSnapshot> listener, Duration interval) {
        this.check = Objects.requireNonNull(check);
        this.listener = Objects.requireNonNull(listener);
        this.interval = Objects.requireNonNull(interval);
        if (interval.isNegative() || interval.isZero()) throw new IllegalArgumentException("interval must be positive");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "terminal-authorization-monitor");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        scheduleNext(interval);
    }

    public synchronized void checkNow() {
        if (!started) return;
        if (scheduledCheck != null) scheduledCheck.cancel(false);
        scheduledCheck = scheduler.schedule(this::checkOnce, 0, TimeUnit.MILLISECONDS);
    }
    public void invalidatePending() { generation.incrementAndGet(); }

    private void checkOnce() {
        if (!started || !inFlight.compareAndSet(false, true)) return;
        long checkGeneration = generation.get();
        try {
            check.get().whenComplete((snapshot, error) -> {
                try {
                    if (!scheduler.isShutdown() && generation.get() == checkGeneration && snapshot != null) listener.accept(snapshot);
                } finally {
                    inFlight.set(false);
                    Duration delay = snapshot == null ? interval : snapshot.retryAfter().filter(value -> !value.isNegative())
                            .map(value -> value.compareTo(interval) > 0 ? value : interval).orElse(interval);
                    scheduleNext(delay);
                }
            });
        } catch (RuntimeException error) {
            inFlight.set(false);
            scheduleNext(interval);
        }
    }

    private synchronized void scheduleNext(Duration delay) {
        if (!started || scheduler.isShutdown()) return;
        scheduledCheck = scheduler.schedule(this::checkOnce, Math.max(1, delay.toMillis()), TimeUnit.MILLISECONDS);
    }

    @Override public synchronized void close() {
        started = false;
        if (scheduledCheck != null) scheduledCheck.cancel(false);
        scheduler.shutdownNow();
    }
}
