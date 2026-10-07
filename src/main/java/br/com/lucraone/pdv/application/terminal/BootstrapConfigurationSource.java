package br.com.lucraone.pdv.application.terminal;

import java.util.Optional;

/**
 * Reads the optional bootstrap configuration of this terminal.
 */
public interface BootstrapConfigurationSource {

    /**
     * Returns empty when no bootstrap file exists.
     *
     * @throws InvalidBootstrapConfigurationException when the file exists but is invalid
     */
    Optional<BootstrapConfiguration> load();

    String location();
}
