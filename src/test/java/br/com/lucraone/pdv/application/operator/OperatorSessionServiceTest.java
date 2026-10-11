package br.com.lucraone.pdv.application.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.OperatorCredential;
import br.com.lucraone.pdv.application.api.OperatorLoginCommand;
import br.com.lucraone.pdv.application.api.OperatorLoginResult;
import br.com.lucraone.pdv.application.api.OperatorSessionData;
import br.com.lucraone.pdv.application.api.PdvApiException;
import br.com.lucraone.pdv.application.api.PdvApiFailure;
import br.com.lucraone.pdv.application.api.PdvBackendGateway;
import br.com.lucraone.pdv.application.api.PdvFailureKind;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OperatorSessionServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final MachineCredential MACHINE = new MachineCredential("Bearer", "machine-test", NOW.plusSeconds(3600));
    private static final OperatorSessionData SESSION = new OperatorSessionData("session-1", "active", NOW.plusSeconds(3600),
            "op-1", "Operator", "operator", null);
    private static final OperatorLoginResult LOGIN = new OperatorLoginResult(SESSION,
            new OperatorCredential("PdvOperatorSession", "human-test", NOW.plusSeconds(3600)), "req-login");

    @Test void scheduledRevalidationPublishesDefinitiveRevocationAndClearsHumanCredential() {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicReference<CompletableFuture<OperatorSessionData>> revalidate = new AtomicReference<>();
        PdvBackendGateway gateway = gateway(revalidate);
        OperatorSessionService service = new OperatorSessionService(gateway, Clock.fixed(NOW, ZoneOffset.UTC), worker, () -> MACHINE);
        try {
            assertEquals(OperatorAuthState.AUTHENTICATED, service.login(MACHINE, new OperatorLoginCommand("operator", "pw")).join().state());
            AtomicReference<OperatorAuthSnapshot> event = new AtomicReference<>();
            service.addListener(event::set);
            revalidate.set(CompletableFuture.failedFuture(new PdvApiException(PdvApiFailure.of(PdvFailureKind.OPERATOR_SESSION_INVALID, "req-invalid"))));
            assertEquals(OperatorAuthState.SESSION_INVALID, service.revalidate(MACHINE).join().state());
            assertEquals(OperatorAuthState.SESSION_INVALID, event.get().state());
            assertEquals(OperatorAuthState.NOT_AUTHENTICATED, service.snapshot().state());
        } finally { service.close(); worker.shutdownNow(); }
    }

    @Test void lateRevalidationCannotRestoreSessionAfterLocalInvalidation() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch requestStarted = new CountDownLatch(1);
        CompletableFuture<OperatorSessionData> pending = new CompletableFuture<>();
        PdvBackendGateway gateway = gateway(new AtomicReference<>(pending), requestStarted);
        OperatorSessionService service = new OperatorSessionService(gateway, Clock.fixed(NOW, ZoneOffset.UTC), worker, () -> MACHINE);
        try {
            service.login(MACHINE, new OperatorLoginCommand("operator", "pw")).join();
            var result = service.revalidate(MACHINE);
            assertTrue(requestStarted.await(1, TimeUnit.SECONDS));
            service.invalidateLocal();
            pending.complete(SESSION);
            assertEquals(OperatorAuthState.NOT_AUTHENTICATED, result.get(1, TimeUnit.SECONDS).state());
            assertEquals(OperatorAuthState.NOT_AUTHENTICATED, service.snapshot().state());
        } finally { service.close(); worker.shutdownNow(); }
    }

    private static PdvBackendGateway gateway(AtomicReference<CompletableFuture<OperatorSessionData>> revalidate) {
        return gateway(revalidate, new CountDownLatch(0));
    }

    private static PdvBackendGateway gateway(AtomicReference<CompletableFuture<OperatorSessionData>> revalidate,
            CountDownLatch started) {
        return new PdvBackendGateway() {
            public CompletableFuture<br.com.lucraone.pdv.application.api.BackendHealth> health() { throw new UnsupportedOperationException(); }
            public CompletableFuture<br.com.lucraone.pdv.application.api.PairedTerminal> pairTerminal(br.com.lucraone.pdv.application.api.PairTerminalCommand command) { throw new UnsupportedOperationException(); }
            public CompletableFuture<br.com.lucraone.pdv.application.api.CurrentTerminal> currentTerminal(MachineCredential c) { throw new UnsupportedOperationException(); }
            public CompletableFuture<br.com.lucraone.pdv.application.api.OperatorListResult> listOperators(MachineCredential c) { throw new UnsupportedOperationException(); }
            public CompletableFuture<OperatorLoginResult> loginOperator(MachineCredential c, OperatorLoginCommand cmd) { return CompletableFuture.completedFuture(LOGIN); }
            public CompletableFuture<OperatorSessionData> getOperatorSession(MachineCredential c, String token) { throw new UnsupportedOperationException(); }
            public CompletableFuture<OperatorSessionData> revalidateOperatorSession(MachineCredential c, String token) { started.countDown(); return revalidate.get(); }
            public CompletableFuture<Void> logoutOperator(MachineCredential c, String token) { return CompletableFuture.completedFuture(null); }
            public CompletableFuture<br.com.lucraone.pdv.application.api.CredentialRotationResult> prepareRotation(MachineCredential c, br.com.lucraone.pdv.application.api.CredentialRotationCommand cmd) { throw new UnsupportedOperationException(); }
            public CompletableFuture<Instant> confirmRotation(MachineCredential c, String id) { throw new UnsupportedOperationException(); }
        };
    }
}
