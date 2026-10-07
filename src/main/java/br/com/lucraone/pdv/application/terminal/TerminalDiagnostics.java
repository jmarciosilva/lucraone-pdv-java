package br.com.lucraone.pdv.application.terminal;

import br.com.lucraone.pdv.domain.terminal.ProvisioningStatus;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/**
 * Local, non-sensitive snapshot used to identify and support this terminal. It is never sent anywhere and
 * must not carry secrets.
 */
public record TerminalDiagnostics(
        UUID installationId,
        ProvisioningStatus provisioningStatus,
        Optional<String> terminalId,
        Optional<String> branchId,
        String databaseLocation,
        BootstrapState bootstrapState,
        String bootstrapLocation,
        Optional<String> environment,
        Optional<URI> apiBaseUrl,
        String applicationVersion,
        String operatingSystem
) {

    public enum BootstrapState {
        ABSENT,
        LOADED
    }
}
