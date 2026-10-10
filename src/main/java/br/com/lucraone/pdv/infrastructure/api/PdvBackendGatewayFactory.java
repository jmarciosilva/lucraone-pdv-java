package br.com.lucraone.pdv.infrastructure.api;

import br.com.lucraone.pdv.application.api.PdvBackendGateway;
import br.com.lucraone.pdv.application.terminal.BootstrapConfiguration;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds the backend adapter from the phase 3 bootstrap configuration, which is the single source of the
 * API address. No environment URL is ever written into the source tree.
 *
 * <p>An absent {@code api.base-url} is a valid state, not a defect: a terminal may legitimately start
 * before anyone configured a backend. The result is therefore {@link Optional}, so the caller can report
 * "backend not configured" instead of catching an exception.
 */
public final class PdvBackendGatewayFactory {

    private PdvBackendGatewayFactory() {
    }

    public static Optional<PdvBackendGateway> fromBootstrap(BootstrapConfiguration configuration) {
        return fromBootstrap(configuration, PdvApiSettings.defaults());
    }

    public static Optional<PdvBackendGateway> fromBootstrap(
            BootstrapConfiguration configuration,
            PdvApiSettings settings
    ) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(settings, "settings must not be null");

        return configuration.apiBaseUrl().map(baseUrl -> create(baseUrl, settings));
    }

    /**
     * @throws IllegalArgumentException when the address cannot be used as an API base URL, for instance
     *                                  plain http against a remote host
     */
    public static PdvBackendGateway create(URI apiBaseUrl, PdvApiSettings settings) {
        return new HttpPdvBackendGateway(apiBaseUrl, settings);
    }
}
