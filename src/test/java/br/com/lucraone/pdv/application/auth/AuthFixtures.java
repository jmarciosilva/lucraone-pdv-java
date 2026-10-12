package br.com.lucraone.pdv.application.auth;

import br.com.lucraone.pdv.application.api.*;
import br.com.lucraone.pdv.application.terminal.TerminalRepository;
import br.com.lucraone.pdv.domain.terminal.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

final class AuthFixtures {
    static final Instant NOW=Instant.parse("2026-10-10T12:00:00Z");
    static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    final Local local=new Local(); final Credentials credentials=new Credentials(); final Validations validations=new Validations(); final Gateway gateway=new Gateway(local);
    TerminalContext context() { return context(local.installation.installationId(),"terminal-test","branch-test"); }
    static TerminalContext context(UUID installation,String terminal,String branch) {
        return new TerminalContext(new TerminalContext.Terminal(terminal,"Synthetic","ACTIVE",installation.toString()),new TerminalContext.Tenant("tenant-test","Synthetic"),new TerminalContext.Company("company-test","Synthetic"),new TerminalContext.Branch(branch,"Synthetic","001"));
    }
    void provisioned() { local.provisioning=new TerminalProvisioning("terminal-test","branch-test",NOW); credentials.value=new StoredMachineCredential(UUID.randomUUID(),new MachineCredential("Bearer","lucraone-test-secret-"+UUID.randomUUID(),NOW.plusSeconds(3600))); gateway.current=new CurrentTerminal(context(),credentials.value.credential().expiresAt(),"request-current"); }
    void validated() { validations.value=new TerminalValidation(credentials.value.generation(),local.installation.installationId(),"terminal-test","branch-test","backend-test",NOW.minusSeconds(60)); }
    TerminalProvisioningService provisioner() { return new TerminalProvisioningService(local,credentials,validations,gateway,CLOCK); }
    TerminalAuthenticationService auth() { return auth(CLOCK); }
    TerminalAuthenticationService auth(Clock clock) { return new TerminalAuthenticationService(local,credentials,validations,Optional.of(gateway),"backend-test",clock,Runnable::run); }
    static final class Local implements TerminalRepository {
        Installation installation=new Installation(UUID.randomUUID(),NOW.minusSeconds(100)); TerminalProvisioning provisioning; boolean failSave;
        public Installation ensureInstallation(Installation candidate) { if(installation==null)installation=candidate; return installation; }
        public Optional<Installation> findInstallation(){ return Optional.ofNullable(installation); }
        public Optional<TerminalProvisioning> findProvisioning(){ return Optional.ofNullable(provisioning); }
        public void saveProvisioning(TerminalProvisioning p){ if(failSave)throw new IllegalStateException("synthetic-db-failure"); provisioning=p; }
    }
    static final class Credentials implements MachineCredentialRepository {
        StoredMachineCredential value; int saves,deletes,probes; boolean unavailable,failSave,failCompensation,failDelete,failProbe,unavailableOnNextSave;
        public void save(StoredMachineCredential v){ saves++; if(unavailableOnNextSave){unavailableOnNextSave=false;throw new CredentialStorageException(CredentialStorageException.Kind.UNAVAILABLE);} if(failSave || (failCompensation && saves>1))throw new CredentialStorageException(CredentialStorageException.Kind.WRITE_FAILED); value=v; }
        public Optional<StoredMachineCredential> load(){ if(unavailable)throw new CredentialStorageException(CredentialStorageException.Kind.UNAVAILABLE); return Optional.ofNullable(value); }
        public void delete(){ deletes++; if(failDelete)throw new CredentialStorageException(CredentialStorageException.Kind.DELETE_FAILED); value=null; }
        public void verifyWritable(){ probes++; if(unavailable || failProbe)throw new CredentialStorageException(CredentialStorageException.Kind.UNAVAILABLE); }
    }
    static final class Validations implements TerminalValidationRepository {
        TerminalValidation value; boolean failSave,failInvalidate;
        public Optional<TerminalValidation> load(){return Optional.ofNullable(value);}
        public void save(TerminalValidation v){if(failSave)throw new IllegalStateException();value=v;}
        public void invalidate(){if(failInvalidate)throw new IllegalStateException();value=null;}
    }
    static class Gateway implements PdvBackendGateway {
        final Local local; CurrentTerminal current; TerminalContext pairedContext; RuntimeException failure; MachineCredential pairedCredential;
        int pairs,reads; PairTerminalCommand command; CompletableFuture<CurrentTerminal> pending;
        Gateway(Local local){this.local=local;}
        public CompletableFuture<BackendHealth> health(){throw new AssertionError("health not required");}
        public CompletableFuture<PairedTerminal> pairTerminal(PairTerminalCommand c){pairs++;command=c; if(failure!=null)return CompletableFuture.failedFuture(failure); return CompletableFuture.completedFuture(new PairedTerminal(pairedContext==null?context(local.installation.installationId(),"terminal-test","branch-test"):pairedContext,pairedCredential==null?new MachineCredential("Bearer","lucraone-test-secret-"+UUID.randomUUID(),NOW.plusSeconds(3600)):pairedCredential,"request-pair"));}
        public CompletableFuture<CurrentTerminal> currentTerminal(MachineCredential c){reads++;if(pending!=null)return pending;if(failure!=null)return CompletableFuture.failedFuture(failure);return CompletableFuture.completedFuture(current==null?new CurrentTerminal(context(local.installation.installationId(),"terminal-test","branch-test"),c.expiresAt(),"request-current"):current);}
        public CompletableFuture<br.com.lucraone.pdv.application.api.OperatorListResult> listOperators(MachineCredential c){throw new AssertionError("listOperators not required");}
        public CompletableFuture<br.com.lucraone.pdv.application.api.OperatorLoginResult> loginOperator(MachineCredential c,br.com.lucraone.pdv.application.api.OperatorLoginCommand cmd){throw new AssertionError("loginOperator not required");}
        public CompletableFuture<br.com.lucraone.pdv.application.api.OperatorSessionData> getOperatorSession(MachineCredential c,String t){throw new AssertionError("getOperatorSession not required");}
        public CompletableFuture<br.com.lucraone.pdv.application.api.OperatorSessionData> revalidateOperatorSession(MachineCredential c,String t){throw new AssertionError("revalidateOperatorSession not required");}
        public CompletableFuture<Void> logoutOperator(MachineCredential c,String t){throw new AssertionError("logoutOperator not required");}
        public CompletableFuture<br.com.lucraone.pdv.application.api.CredentialRotationResult> prepareRotation(MachineCredential c,br.com.lucraone.pdv.application.api.CredentialRotationCommand cmd){throw new AssertionError("prepareRotation not required");}
        public CompletableFuture<java.time.Instant> confirmRotation(MachineCredential c,String id){throw new AssertionError("confirmRotation not required");}
        void fail(PdvFailureKind kind){failure=new PdvApiException(PdvApiFailure.of(kind,"request-failure"));}
    }
}
