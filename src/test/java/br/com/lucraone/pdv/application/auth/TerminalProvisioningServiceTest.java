package br.com.lucraone.pdv.application.auth;

import br.com.lucraone.pdv.application.api.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class TerminalProvisioningServiceTest {
    AuthFixtures f;
    @BeforeEach void setup(){f=new AuthFixtures();}
    @Test void successPersistsSecretAndNonSecretReferences(){var result=f.provisioner().pair("synthetic-code");assertEquals(TerminalAuthState.CREDENTIAL_PRESENT_UNVERIFIED,result.state());assertNotNull(f.credentials.value);assertEquals("terminal-test",f.local.provisioning.terminalId());}
    @Test void usesExistingInstallation(){var id=f.local.installation.installationId();f.provisioner().pair("synthetic-code");assertEquals(id,f.gateway.command.installationId());assertEquals(id,f.local.installation.installationId());}
    @Test void divergentInstallationNeverSavesCredential(){f.gateway.pairedContext=AuthFixtures.context(UUID.randomUUID(),"terminal-test","branch-test");var e=assertThrows(TerminalAuthException.class,()->f.provisioner().pair("synthetic-code"));assertEquals(TerminalAuthException.Kind.CONTEXT_MISMATCH,e.kind());assertNull(f.credentials.value);}
    @Test void unavailableStoreBlocksMutatingCall(){f.credentials.unavailable=true;assertThrows(CredentialStorageException.class,()->f.provisioner().pair("synthetic-code"));assertEquals(0,f.gateway.pairs);}
    @Test void credentialSaveFailureDoesNotSaveProvisioning(){f.credentials.failSave=true;assertThrows(TerminalAuthException.class,()->f.provisioner().pair("synthetic-code"));assertNull(f.local.provisioning);}
    @Test void sqliteFailureDeletesNewCredential(){f.local.failSave=true;var e=assertThrows(TerminalAuthException.class,()->f.provisioner().pair("synthetic-code"));assertEquals(TerminalAuthException.Kind.PERSISTENCE_FAILED,e.kind());assertNull(f.credentials.value);assertEquals(1,f.credentials.deletes);}
    @Test void compensationFailureIsExplicit(){f.local.failSave=true;f.credentials.failDelete=true;var e=assertThrows(TerminalAuthException.class,()->f.provisioner().pair("synthetic-code"));assertEquals(TerminalAuthException.Kind.COMPENSATION_FAILED,e.kind());assertNotNull(f.credentials.value);}
    @Test void pairingHasNoServiceRetry(){f.gateway.fail(PdvFailureKind.TIMEOUT);assertThrows(TerminalAuthException.class,()->f.provisioner().pair("synthetic-code"));assertEquals(1,f.gateway.pairs);}
    @Test void pairingCodeNeverReachesStorage(){f.provisioner().pair("sensitive-synthetic-pairing");assertFalse(f.credentials.value.toString().contains("sensitive-synthetic-pairing"));assertFalse(f.credentials.value.credential().accessToken().contains("sensitive-synthetic-pairing"));assertFalse(f.local.provisioning.toString().contains("sensitive-synthetic-pairing"));}
    @Test void rotationOverwritesOldCredential(){f.provisioned();var old=f.credentials.value;f.provisioner().pair("synthetic-code");assertNotEquals(old,f.credentials.value);}
    @Test void failedRotationRestoresOldCredentialAfterSqliteFailure(){f.provisioned();f.validated();var old=f.credentials.value;var refs=f.local.provisioning;f.local.failSave=true;assertThrows(TerminalAuthException.class,()->f.provisioner().pair("synthetic-code"));assertEquals(old,f.credentials.value);assertEquals(refs,f.local.provisioning);assertNull(f.validations.value);}
    @Test void failedSavePreservesOldCredential(){f.provisioned();var old=f.credentials.value;f.credentials.failSave=true;assertThrows(TerminalAuthException.class,()->f.provisioner().pair("synthetic-code"));assertEquals(old,f.credentials.value);}
    @Test void failedRotationCompensationIsExplicit(){f.provisioned();f.local.failSave=true;f.credentials.failCompensation=true;assertEquals(TerminalAuthException.Kind.COMPENSATION_FAILED,assertThrows(TerminalAuthException.class,()->f.provisioner().pair("synthetic-code")).kind());}
    @Test void evidenceMustBeInvalidatedBeforePairing(){f.validations.failInvalidate=true;assertThrows(TerminalAuthException.class,()->f.provisioner().pair("synthetic-code"));assertEquals(0,f.gateway.pairs);}
    @Test void expiredPairingResponseIsRejected(){f.gateway.pairedCredential=new MachineCredential("Bearer","synthetic",AuthFixtures.NOW);assertThrows(TerminalAuthException.class,()->f.provisioner().pair("synthetic-code"));assertNull(f.credentials.value);}
    @Test void invalidSchemeIsRejectedBeforeSaving(){f.gateway.pairedCredential=new MachineCredential("Basic","synthetic",AuthFixtures.NOW.plusSeconds(3600));assertThrows(TerminalAuthException.class,()->f.provisioner().pair("synthetic-code"));assertNull(f.credentials.value);}
    @Test void unknownBackendExceptionIsSanitized(){f.gateway.failure=new IllegalStateException("sensitive-synthetic-pairing");var e=assertThrows(TerminalAuthException.class,()->f.provisioner().pair("sensitive-synthetic-pairing"));assertNull(e.getCause());assertFalse(e.toString().contains("sensitive-synthetic-pairing"));}
    @Test void readableButUnwritableStoreBlocksPairingBeforeInvalidatingProof() {
        f.provisioned(); f.validated();
        var previous = f.credentials.value;
        var proof = f.validations.value;
        f.credentials.failProbe = true;
        assertThrows(CredentialStorageException.class, () -> f.provisioner().pair("synthetic-code"));
        assertEquals(0, f.gateway.pairs);
        assertEquals(previous, f.credentials.value);
        assertEquals(proof, f.validations.value);
    }

}
