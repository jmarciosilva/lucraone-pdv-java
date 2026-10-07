package br.com.lucraone.pdv.application.terminal;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * Non-secret startup parameters read from the local bootstrap file. The API address is only stored here;
 * no connection is made with it.
 */
public record BootstrapConfiguration(Optional<String> environment, Optional<URI> apiBaseUrl) {

    public BootstrapConfiguration {
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(apiBaseUrl, "apiBaseUrl must not be null");
    }
}
