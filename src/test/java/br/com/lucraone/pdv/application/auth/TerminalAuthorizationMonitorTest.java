package br.com.lucraone.pdv.application.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TerminalAuthorizationMonitorTest {
    @Test void doesNotOverlapChecksAndStopsAfterClose() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<TerminalAuthSnapshot> first = new CompletableFuture<>();
        CountDownLatch observed = new CountDownLatch(1);
        TerminalAuthorizationMonitor monitor = new TerminalAuthorizationMonitor(() -> {
            return calls.incrementAndGet() == 1 ? first : CompletableFuture.completedFuture(TerminalAuthSnapshot.of(TerminalAuthState.AUTHENTICATED));
        }, ignored -> observed.countDown(), Duration.ofMillis(15));
        monitor.start();
        Thread.sleep(90);
        assertEquals(1, calls.get(), "a pending validation must suppress overlapping polls");
        first.complete(TerminalAuthSnapshot.of(TerminalAuthState.AUTHENTICATED));
        assertTrue(observed.await(1, TimeUnit.SECONDS));
        monitor.close();
        int afterClose = calls.get();
        Thread.sleep(60);
        assertEquals(afterClose, calls.get());
    }

    @Test void honorsRetryAfterAsMinimumPollingDelay() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch twice = new CountDownLatch(2);
        TerminalAuthorizationMonitor monitor = new TerminalAuthorizationMonitor(() -> {
            int call = calls.incrementAndGet();
            if (call == 1) return CompletableFuture.completedFuture(new TerminalAuthSnapshot(
                    TerminalAuthState.VALIDATION_FAILED, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(Duration.ofMillis(130))));
            twice.countDown();
            return CompletableFuture.completedFuture(TerminalAuthSnapshot.of(TerminalAuthState.AUTHENTICATED));
        }, ignored -> {}, Duration.ofMillis(10));
        long started = System.nanoTime();
        monitor.start();
        assertTrue(twice.await(2, TimeUnit.SECONDS));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 120);
        monitor.close();
    }
}
