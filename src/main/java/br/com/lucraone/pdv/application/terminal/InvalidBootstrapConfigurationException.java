package br.com.lucraone.pdv.application.terminal;

/**
 * Indicates that the bootstrap file exists but cannot be used. Messages never echo configured values.
 */
public final class InvalidBootstrapConfigurationException extends RuntimeException {

    public InvalidBootstrapConfigurationException(String message) {
        super(message);
    }

    public InvalidBootstrapConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
