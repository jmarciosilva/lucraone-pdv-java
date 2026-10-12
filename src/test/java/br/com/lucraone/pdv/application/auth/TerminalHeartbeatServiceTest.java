package br.com.lucraone.pdv.application.auth;

import static org.junit.jupiter.api.Assertions.*;
import br.com.lucraone.pdv.application.api.*;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class TerminalHeartbeatServiceTest {
    private static MachineCredential credential(String token) { return new MachineCredential("Bearer", token, Instant.now().plusSeconds(3600)); }
    private static boolean await(BooleanSupplier check) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < end) { if (check.getAsBoolean()) return true; Thread.sleep(5); }
        return check.getAsBoolean();
    }
    private static class Gateway implements PdvBackendGateway {
        final AtomicInteger calls = new AtomicInteger(), active = new AtomicInteger(), maxActive = new AtomicInteger();
        volatile MachineCredential last; volatile CompletableFuture<Void> next = CompletableFuture.completedFuture(null);
        public CompletableFuture<Void> heartbeat(MachineCredential c) {
            last = c; calls.incrementAndGet(); int now = active.incrementAndGet(); maxActive.accumulateAndGet(now, Math::max);
            CompletableFuture<Void> response = next;
            return response.whenComplete((v,e) -> active.decrementAndGet());
        }
        public CompletableFuture<BackendHealth> health(){throw new UnsupportedOperationException();}
        public CompletableFuture<PairedTerminal> pairTerminal(PairTerminalCommand c){throw new UnsupportedOperationException();}
        public CompletableFuture<CurrentTerminal> currentTerminal(MachineCredential c){throw new UnsupportedOperationException();}
        public CompletableFuture<OperatorListResult> listOperators(MachineCredential c){throw new UnsupportedOperationException();}
        public CompletableFuture<OperatorLoginResult> loginOperator(MachineCredential c,OperatorLoginCommand x){throw new UnsupportedOperationException();}
        public CompletableFuture<OperatorSessionData> getOperatorSession(MachineCredential c,String t){throw new UnsupportedOperationException();}
        public CompletableFuture<OperatorSessionData> revalidateOperatorSession(MachineCredential c,String t){throw new UnsupportedOperationException();}
        public CompletableFuture<Void> logoutOperator(MachineCredential c,String t){throw new UnsupportedOperationException();}
        public CompletableFuture<CredentialRotationResult> prepareRotation(MachineCredential c,CredentialRotationCommand x){throw new UnsupportedOperationException();}
        public CompletableFuture<Instant> confirmRotation(MachineCredential c,String t){throw new UnsupportedOperationException();}
    }
    @Test void startsOnlyWhenAuthorizedAndUsesFreshCredentialEachIntervalWithoutOverlap() throws Exception {
        Gateway gateway = new Gateway(); AtomicReference<MachineCredential> current = new AtomicReference<>(credential("old-token"));
        CompletableFuture<Void> pending = new CompletableFuture<>(); gateway.next = pending;
        try (var service = new TerminalHeartbeatService(gateway, current::get, () -> {}, Duration.ofMillis(35))) {
            Thread.sleep(70); assertEquals(0, gateway.calls.get());
            service.setAuthorized(true);
            assertTrue(await(() -> gateway.calls.get() == 1));
            current.set(credential("rotated-token")); Thread.sleep(100);
            assertEquals(1, gateway.calls.get(), "pending request must prevent overlap");
            pending.complete(null); gateway.next = CompletableFuture.completedFuture(null);
            assertTrue(await(() -> gateway.calls.get() >= 2));
            assertEquals("rotated-token", gateway.last.accessToken());
            service.setAuthorized(false); int stopped = gateway.calls.get(); Thread.sleep(100); assertEquals(stopped, gateway.calls.get());
            assertEquals(1, gateway.maxActive.get());
        }
    }
    @Test void networkFailureRetriesButRevocationSuspendsHeartbeatAndSignalsMonitor() throws Exception {
        Gateway gateway = new Gateway(); AtomicInteger revoked = new AtomicInteger();
        gateway.next = CompletableFuture.failedFuture(new PdvApiException(PdvApiFailure.of(PdvFailureKind.NETWORK_UNAVAILABLE,"rid")));
        try (var service = new TerminalHeartbeatService(gateway, () -> credential("secret-test"), revoked::incrementAndGet, Duration.ofMillis(30))) {
            service.setAuthorized(true); assertTrue(await(() -> gateway.calls.get() >= 2)); assertEquals(0, revoked.get());
            gateway.next = CompletableFuture.failedFuture(new PdvApiException(PdvApiFailure.of(PdvFailureKind.FORBIDDEN,"rid")));
            assertTrue(await(() -> gateway.calls.get() >= 3)); assertTrue(await(() -> revoked.get() == 1));
            int count = gateway.calls.get(); Thread.sleep(90); assertEquals(count, gateway.calls.get());
        }
    }
    @Test void closeCancelsFutureSends() throws Exception {
        Gateway gateway = new Gateway(); var service = new TerminalHeartbeatService(gateway, () -> credential("secret-test"), () -> {}, Duration.ofMillis(20));
        service.setAuthorized(true); assertTrue(await(() -> gateway.calls.get() > 0)); service.close(); int count = gateway.calls.get();
        Thread.sleep(80); assertEquals(count, gateway.calls.get());
    }
}
