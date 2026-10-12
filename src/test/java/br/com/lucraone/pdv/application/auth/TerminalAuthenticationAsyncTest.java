package br.com.lucraone.pdv.application.auth;

import br.com.lucraone.pdv.application.api.CurrentTerminal;
import java.time.*;
import java.util.Optional;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerminalAuthenticationAsyncTest {
    @Test void pendingNetworkNeverBlocksCallerAndSerializesLogout() throws Exception {
        var f = new AuthFixtures(); f.provisioned();
        f.gateway.pending = new CompletableFuture<>();
        var worker = Executors.newSingleThreadExecutor();
        try {
            var auth = new TerminalAuthenticationService(f.local, f.credentials, f.validations,
                    Optional.of(f.gateway), "backend-test", AuthFixtures.CLOCK, worker);
            var restore = auth.restore();
            var forget = auth.forget();
            assertFalse(restore.isDone()); assertFalse(forget.isDone());
            f.gateway.pending.complete(f.gateway.current);
            assertEquals(TerminalAuthState.AUTHENTICATED, restore.get(5, TimeUnit.SECONDS).state());
            assertEquals(TerminalAuthState.PROVISIONED_NO_CREDENTIAL, forget.get(5, TimeUnit.SECONDS).state());
            assertNull(f.credentials.value); assertNull(f.validations.value);
        } finally {
            f.gateway.pending.complete(f.gateway.current);
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
    @Test void expiryDuringNetworkWaitCannotAuthenticate() throws Exception {
        var f = new AuthFixtures(); f.provisioned();
        var now = new java.util.concurrent.atomic.AtomicReference<>(AuthFixtures.NOW);
        Clock clock = new Clock() {
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return now.get();}
        };
        var pending = new CompletableFuture<CurrentTerminal>();
        var entered = new CountDownLatch(1);
        var gateway = new br.com.lucraone.pdv.application.api.PdvBackendGateway() {
            public CompletableFuture<br.com.lucraone.pdv.application.api.BackendHealth> health(){throw new AssertionError();}
            public CompletableFuture<br.com.lucraone.pdv.application.api.PairedTerminal> pairTerminal(br.com.lucraone.pdv.application.api.PairTerminalCommand c){throw new AssertionError();}
            public CompletableFuture<CurrentTerminal> currentTerminal(br.com.lucraone.pdv.application.api.MachineCredential c){entered.countDown();return pending;}
            public CompletableFuture<br.com.lucraone.pdv.application.api.OperatorListResult> listOperators(br.com.lucraone.pdv.application.api.MachineCredential c){throw new AssertionError();}
            public CompletableFuture<br.com.lucraone.pdv.application.api.OperatorLoginResult> loginOperator(br.com.lucraone.pdv.application.api.MachineCredential c,br.com.lucraone.pdv.application.api.OperatorLoginCommand cmd){throw new AssertionError();}
            public CompletableFuture<br.com.lucraone.pdv.application.api.OperatorSessionData> getOperatorSession(br.com.lucraone.pdv.application.api.MachineCredential c,String t){throw new AssertionError();}
            public CompletableFuture<br.com.lucraone.pdv.application.api.OperatorSessionData> revalidateOperatorSession(br.com.lucraone.pdv.application.api.MachineCredential c,String t){throw new AssertionError();}
            public CompletableFuture<Void> logoutOperator(br.com.lucraone.pdv.application.api.MachineCredential c,String t){throw new AssertionError();}
            public CompletableFuture<br.com.lucraone.pdv.application.api.CredentialRotationResult> prepareRotation(br.com.lucraone.pdv.application.api.MachineCredential c,br.com.lucraone.pdv.application.api.CredentialRotationCommand cmd){throw new AssertionError();}
            public CompletableFuture<java.time.Instant> confirmRotation(br.com.lucraone.pdv.application.api.MachineCredential c,String id){throw new AssertionError();}
        };
        var worker = Executors.newSingleThreadExecutor();
        try {
            var auth=new TerminalAuthenticationService(f.local,f.credentials,f.validations,Optional.of(gateway),"backend-test",clock,worker);
            var result=auth.restore(); assertTrue(entered.await(5,TimeUnit.SECONDS));
            now.set(f.credentials.value.credential().expiresAt()); pending.complete(f.gateway.current);
            assertEquals(TerminalAuthState.REAUTHENTICATION_REQUIRED,result.get(5,TimeUnit.SECONDS).state());
            assertNull(f.validations.value);
        } finally {
            pending.complete(f.gateway.current);
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
