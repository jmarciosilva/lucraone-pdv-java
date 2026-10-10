package br.com.lucraone.pdv.infrastructure.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.application.api.BackendHealth;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in smoke check against a real backend. It is skipped unless {@code LUCRAONE_PDV_SMOKE=1}, so the
 * Maven suite stays fully independent of the Internet, and the address comes from
 * {@code LUCRAONE_PDV_BASE_URL} so that no environment URL is ever hardcoded in the source tree.
 *
 * <p>It touches the unauthenticated health endpoint only: it creates no terminal, consumes no pairing
 * code and requests no credential.
 */
@EnabledIfEnvironmentVariable(named = "LUCRAONE_PDV_SMOKE", matches = "1")
class PdvHealthSmokeIT {

    @Test
    void theRealBackendAnswersTheVersionOneHealthContract() {
        String configured = System.getenv("LUCRAONE_PDV_BASE_URL");
        assertFalse(
                configured == null || configured.isBlank(),
                "set LUCRAONE_PDV_BASE_URL to run the smoke check"
        );

        URI baseUrl = URI.create(configured.strip());
        assertEquals("https", baseUrl.getScheme(), "the smoke check must run over TLS");

        try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(baseUrl)) {
            BackendHealth health = gateway.health().join();

            assertEquals("ok", health.status());
            assertEquals("pdv", health.api());
            assertEquals("v1", health.version());
            assertTrue(health.versionSupported());
            assertFalse(health.requestId().isBlank(), "the backend must correlate the call");
        }
    }
}
