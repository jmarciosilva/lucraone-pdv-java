package br.com.lucraone.pdv.infrastructure.security;
import br.com.lucraone.pdv.application.security.SecretStoreException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LinuxSecretStoreUnavailableIT {
    @Test void unavailableBusIsNeverReportedAsMissing() {
        assertEquals("1", System.getenv("LUCRAONE_TEST_UNAVAILABLE_BUS"), "Run only with isolated invalid D-Bus address");
        var store = new LinuxSecretStore();
        var failure = assertThrows(SecretStoreException.class, () -> store.load("lucraone-test-missing"));
        assertEquals(SecretStoreException.Kind.UNAVAILABLE, failure.kind());
    }
}
