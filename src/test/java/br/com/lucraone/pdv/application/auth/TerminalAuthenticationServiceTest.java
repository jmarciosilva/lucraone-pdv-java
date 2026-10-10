package br.com.lucraone.pdv.application.auth;

import br.com.lucraone.pdv.application.api.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class TerminalAuthenticationServiceTest {
    AuthFixtures f;
    @BeforeEach void setup(){f=new AuthFixtures();}
    TerminalAuthSnapshot restore(){return f.auth().restore().join();}
    void failure(PdvFailureKind kind){f.gateway.fail(kind);}
    @Test void emptyInstallationIsNotProvisioned(){assertEquals(TerminalAuthState.NOT_PROVISIONED,restore().state());}
    @Test void provisionedCredentialValidatesOnline(){f.provisioned();assertEquals(TerminalAuthState.AUTHENTICATED,restore().state());assertNotNull(f.validations.value);}
    @Test void provisioningWithoutCredentialIsExplicit(){f.provisioned();f.credentials.value=null;assertEquals(TerminalAuthState.PROVISIONED_NO_CREDENTIAL,restore().state());}
    @Test void credentialWithoutProvisioningRequiresRepairing(){f.provisioned();f.local.provisioning=null;assertEquals(TerminalAuthState.INCONSISTENT_STATE,restore().state());assertEquals(0,f.gateway.reads);}
    @Test void expiredCredentialIsNotSent(){f.provisioned();assertEquals(TerminalAuthState.REAUTHENTICATION_REQUIRED,f.auth(Clock.fixed(f.credentials.value.credential().expiresAt(),ZoneOffset.UTC)).restore().join().state());assertEquals(0,f.gateway.reads);}
    @Test void unavailableStoreIsExplicit(){f.provisioned();f.credentials.unavailable=true;assertEquals(TerminalAuthState.SECURE_STORE_UNAVAILABLE,restore().state());}
    @Test void beforeExpiryCanAuthenticate(){f.provisioned();assertEquals(TerminalAuthState.AUTHENTICATED,f.auth(Clock.fixed(f.credentials.value.credential().expiresAt().minusNanos(1),ZoneOffset.UTC)).restore().join().state());}
    @Test void afterExpiryRequiresReauthentication(){f.provisioned();assertEquals(TerminalAuthState.REAUTHENTICATION_REQUIRED,f.auth(Clock.fixed(f.credentials.value.credential().expiresAt().plusNanos(1),ZoneOffset.UTC)).restore().join().state());}
    @Test void unauthorizedRemovesCredentialAndProof(){f.provisioned();f.validated();failure(PdvFailureKind.UNAUTHENTICATED);assertEquals(TerminalAuthState.REAUTHENTICATION_REQUIRED,restore().state());assertNull(f.credentials.value);assertNull(f.validations.value);}
    @Test void forbiddenDoesNotBecomeOffline(){f.provisioned();f.validated();failure(PdvFailureKind.FORBIDDEN);assertEquals(TerminalAuthState.REAUTHENTICATION_REQUIRED,restore().state());assertNull(f.validations.value);}
    @Test void networkWithPriorValidationIsLimitedOffline(){f.provisioned();f.validated();failure(PdvFailureKind.NETWORK_UNAVAILABLE);assertEquals(TerminalAuthState.OFFLINE_LIMITED,restore().state());assertNotNull(f.validations.value);}
    @Test void timeoutWithPriorValidationIsLimitedOffline(){f.provisioned();f.validated();failure(PdvFailureKind.TIMEOUT);assertEquals(TerminalAuthState.OFFLINE_LIMITED,restore().state());}
    @Test void networkWithoutProofIsUnverified(){f.provisioned();failure(PdvFailureKind.NETWORK_UNAVAILABLE);assertEquals(TerminalAuthState.CREDENTIAL_PRESENT_UNVERIFIED,restore().state());}
    @Test void serverErrorDoesNotBecomeOffline(){f.provisioned();f.validated();failure(PdvFailureKind.SERVER_ERROR);assertEquals(TerminalAuthState.VALIDATION_FAILED,restore().state());assertNull(f.validations.value);}
    @Test void invalidResponseDoesNotBecomeOffline(){f.provisioned();f.validated();failure(PdvFailureKind.INVALID_RESPONSE);assertEquals(TerminalAuthState.VALIDATION_FAILED,restore().state());}
    @Test void pairingFailedDoesNotBecomeOffline(){f.provisioned();f.validated();failure(PdvFailureKind.PAIRING_FAILED);assertEquals(TerminalAuthState.VALIDATION_FAILED,restore().state());}
    @Test void divergentInstallationFailsContext(){f.provisioned();f.gateway.current=new CurrentTerminal(AuthFixtures.context(UUID.randomUUID(),"terminal-test","branch-test"),f.credentials.value.credential().expiresAt(),"request-mismatch");assertEquals(TerminalAuthState.CONTEXT_MISMATCH,restore().state());}
    @Test void divergentTerminalFailsContext(){f.provisioned();f.gateway.current=new CurrentTerminal(AuthFixtures.context(f.local.installation.installationId(),"other","branch-test"),f.credentials.value.credential().expiresAt(),"request-mismatch");assertEquals(TerminalAuthState.CONTEXT_MISMATCH,restore().state());}
    @Test void divergentBranchFailsContext(){f.provisioned();f.gateway.current=new CurrentTerminal(AuthFixtures.context(f.local.installation.installationId(),"terminal-test","other"),f.credentials.value.credential().expiresAt(),"request-mismatch");assertEquals(TerminalAuthState.CONTEXT_MISMATCH,restore().state());}
    @Test void divergentExpiryFailsContext(){f.provisioned();f.gateway.current=new CurrentTerminal(f.context(),f.credentials.value.credential().expiresAt().plusSeconds(1),"request-mismatch");assertEquals(TerminalAuthState.CONTEXT_MISMATCH,restore().state());}
    @Test void oldGenerationProofCannotAuthorizeNewTokenOffline(){f.provisioned();f.validated();f.credentials.save(f.credentials.value.credential());failure(PdvFailureKind.NETWORK_UNAVAILABLE);assertEquals(TerminalAuthState.CREDENTIAL_PRESENT_UNVERIFIED,restore().state());}
    @Test void futureProofCannotAuthorizeOffline(){f.provisioned();f.validated();var v=f.validations.value;f.validations.value=new TerminalValidation(v.generation(),v.installationId(),v.terminalId(),v.branchId(),v.backendIdentity(),AuthFixtures.NOW.plusSeconds(1));failure(PdvFailureKind.NETWORK_UNAVAILABLE);assertEquals(TerminalAuthState.CREDENTIAL_PRESENT_UNVERIFIED,restore().state());}
    @Test void otherBackendProofCannotAuthorizeOffline(){f.provisioned();f.validated();var v=f.validations.value;f.validations.value=new TerminalValidation(v.generation(),v.installationId(),v.terminalId(),v.branchId(),"other-backend",v.validatedAt());failure(PdvFailureKind.NETWORK_UNAVAILABLE);assertEquals(TerminalAuthState.CREDENTIAL_PRESENT_UNVERIFIED,restore().state());}
    @Test void failedProofInvalidationPreventsOnlineCall(){f.provisioned();f.validated();f.validations.failInvalidate=true;assertEquals(TerminalAuthState.LOCAL_STATE_UNAVAILABLE,restore().state());assertEquals(0,f.gateway.reads);}
    @Test void failedProofPersistenceNeverAuthenticates(){f.provisioned();f.validations.failSave=true;assertEquals(TerminalAuthState.LOCAL_STATE_UNAVAILABLE,restore().state());}
    @Test void denialWithDeleteFailureCannotRegainOfflineAfterRestart(){f.provisioned();f.validated();f.credentials.failDelete=true;failure(PdvFailureKind.UNAUTHENTICATED);assertEquals(TerminalAuthState.INCONSISTENT_STATE,restore().state());assertNull(f.validations.value);failure(PdvFailureKind.NETWORK_UNAVAILABLE);assertEquals(TerminalAuthState.CREDENTIAL_PRESENT_UNVERIFIED,restore().state());}
    @Test void logoutDeletesCredentialPreservesIdentityAndProvisioning(){f.provisioned();f.validated();var id=f.local.installation.installationId();var refs=f.local.provisioning;assertEquals(TerminalAuthState.PROVISIONED_NO_CREDENTIAL,f.auth().forget().join().state());assertNull(f.credentials.value);assertNull(f.validations.value);assertEquals(id,f.local.installation.installationId());assertEquals(refs,f.local.provisioning);}
    @Test void logoutIsIdempotent(){f.provisioned();var auth=f.auth();auth.forget().join();assertEquals(TerminalAuthState.PROVISIONED_NO_CREDENTIAL,auth.forget().join().state());}
    @Test void failedLogoutIsExplicit(){f.provisioned();f.validated();f.credentials.failDelete=true;assertEquals(TerminalAuthState.INCONSISTENT_STATE,f.auth().forget().join().state());assertNull(f.validations.value);}
    @Test void failureRetainsCorrelationWithoutBackendMessage(){f.provisioned();failure(PdvFailureKind.UNAUTHENTICATED);assertEquals(Optional.of("request-failure"),restore().requestId());}
    @Test void authenticatedSnapshotContainsNoSecret(){f.provisioned();var token=f.credentials.value.credential().accessToken();assertFalse(restore().toString().contains(token));}
    @Test void pairingThenValidationAuthenticates(){assertEquals(TerminalAuthState.AUTHENTICATED,f.auth().pair("synthetic-code").join().state());assertEquals(1,f.gateway.pairs);assertEquals(1,f.gateway.reads);}
    @Test void missingBackendDoesNotPretendOffline(){f.provisioned();f.validated();var auth=new TerminalAuthenticationService(f.local,f.credentials,f.validations,Optional.empty(),"",AuthFixtures.CLOCK,Runnable::run);assertEquals(TerminalAuthState.CREDENTIAL_PRESENT_UNVERIFIED,auth.restore().join().state());assertEquals(TerminalAuthState.BACKEND_NOT_CONFIGURED,auth.pair("synthetic-code").join().state());}
    @Test void storeUnavailableAfterRemotePairingRemainsExplicitAfterSuccessfulCompensation() {
        f.provisioned(); f.validated();
        var previous = f.credentials.value;
        var refs = f.local.provisioning;
        f.credentials.unavailableOnNextSave = true;
        var result = f.auth().pair("synthetic-code").join();
        assertEquals(TerminalAuthState.SECURE_STORE_UNAVAILABLE, result.state());
        assertEquals(Optional.of("request-pair"), result.requestId());
        assertEquals(1, f.gateway.pairs);
        assertEquals(previous, f.credentials.value);
        assertEquals(refs, f.local.provisioning);
        assertNull(f.validations.value);
    }
    @Test void expiryDuringSuccessfulProofWriteRequiresReauthentication() {
        assertExpiryDuringProofWrite(false);
    }
    @Test void expiryDuringOfflineProofWriteRequiresReauthentication() {
        assertExpiryDuringProofWrite(true);
    }
    private void assertExpiryDuringProofWrite(boolean offline) {
        f.provisioned(); f.validated();
        if (offline) failure(PdvFailureKind.NETWORK_UNAVAILABLE);
        var now = new java.util.concurrent.atomic.AtomicReference<>(AuthFixtures.NOW);
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        var validationRepository = new TerminalValidationRepository() {
            public Optional<TerminalValidation> load() { return f.validations.load(); }
            public void save(TerminalValidation proof) {
                f.validations.save(proof);
                now.set(f.credentials.value.credential().expiresAt());
            }
            public void invalidate() { f.validations.invalidate(); }
        };
        var auth = new TerminalAuthenticationService(f.local, f.credentials, validationRepository,
                Optional.of(f.gateway), "backend-test", clock, Runnable::run);
        assertEquals(TerminalAuthState.REAUTHENTICATION_REQUIRED, auth.restore().join().state());
        assertNull(f.validations.value);
    }

}
