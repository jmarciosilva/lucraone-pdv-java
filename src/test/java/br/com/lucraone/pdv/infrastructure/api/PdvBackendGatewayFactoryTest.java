package br.com.lucraone.pdv.infrastructure.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.application.api.PdvBackendGateway;
import br.com.lucraone.pdv.application.terminal.BootstrapConfiguration;
import br.com.lucraone.pdv.infrastructure.api.PdvTestBackend.StubResponse;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Proves the adapter is addressed by the phase 3 bootstrap configuration and by nothing else.
 */
class PdvBackendGatewayFactoryTest {

    @Test
    void buildsNoGatewayWhenTheBackendAddressIsNotConfigured() {
        BootstrapConfiguration configuration =
                new BootstrapConfiguration(Optional.of("homologation"), Optional.empty());

        assertTrue(PdvBackendGatewayFactory.fromBootstrap(configuration).isEmpty());
    }

    @Test
    void buildsAGatewayFromTheConfiguredBaseUrl() {
        BootstrapConfiguration configuration = new BootstrapConfiguration(
                Optional.of("homologation"),
                Optional.of(URI.create("https://exemplo.com.br"))
        );

        Optional<PdvBackendGateway> gateway = PdvBackendGatewayFactory.fromBootstrap(configuration);

        assertTrue(gateway.isPresent());
        assertInstanceOf(HttpPdvBackendGateway.class, gateway.get());
        ((HttpPdvBackendGateway) gateway.get()).close();
    }

    @Test
    void theGatewayBuiltFromConfigurationReallyCallsThatAddress() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, PdvApiFixtures.HEALTH_OK));

            BootstrapConfiguration configuration =
                    new BootstrapConfiguration(Optional.empty(), Optional.of(backend.baseUrl()));
            PdvApiSettings settings =
                    PdvApiSettings.defaults().withRequestTimeout(PdvApiAssertions.TEST_REQUEST_TIMEOUT);

            PdvBackendGateway gateway = PdvBackendGatewayFactory.fromBootstrap(configuration, settings)
                    .orElseThrow();
            try {
                assertEquals("ok", gateway.health().join().status());
            } finally {
                ((HttpPdvBackendGateway) gateway).close();
            }

            assertEquals("/api/v1/pdv/health", backend.singleRequest().path());
        }
    }

    @Test
    void refusesAConfiguredAddressThatWouldWeakenTransportSecurity() {
        BootstrapConfiguration configuration = new BootstrapConfiguration(
                Optional.empty(),
                Optional.of(URI.create("http://exemplo.com.br"))
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> PdvBackendGatewayFactory.fromBootstrap(configuration)
        );
    }
}
