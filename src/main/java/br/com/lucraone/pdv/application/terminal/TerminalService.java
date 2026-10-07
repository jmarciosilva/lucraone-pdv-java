package br.com.lucraone.pdv.application.terminal;

import br.com.lucraone.pdv.application.ApplicationMetadata;
import br.com.lucraone.pdv.application.terminal.TerminalDiagnostics.BootstrapState;
import br.com.lucraone.pdv.domain.terminal.Installation;
import br.com.lucraone.pdv.domain.terminal.ProvisioningStatus;
import br.com.lucraone.pdv.domain.terminal.TerminalProvisioning;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Coordinates the local installation identity, the terminal provisioning state and basic diagnostics.
 * It makes no remote calls: provisioning references are only stored locally.
 */
public final class TerminalService {

    private final TerminalRepository repository;
    private final BootstrapConfigurationSource bootstrapConfiguration;
    private final String databaseLocation;
    private final Clock clock;
    private final Supplier<UUID> installationIdGenerator;

    public TerminalService(
            TerminalRepository repository,
            BootstrapConfigurationSource bootstrapConfiguration,
            String databaseLocation
    ) {
        this(repository, bootstrapConfiguration, databaseLocation, Clock.systemUTC(), UUID::randomUUID);
    }

    TerminalService(
            TerminalRepository repository,
            BootstrapConfigurationSource bootstrapConfiguration,
            String databaseLocation,
            Clock clock,
            Supplier<UUID> installationIdGenerator
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.bootstrapConfiguration = Objects.requireNonNull(bootstrapConfiguration, "bootstrapConfiguration must not be null");
        this.databaseLocation = Objects.requireNonNull(databaseLocation, "databaseLocation must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.installationIdGenerator = Objects.requireNonNull(installationIdGenerator, "installationIdGenerator must not be null");
    }

    /**
     * Ensures the installation identity exists, validates the bootstrap file and describes the terminal.
     * Safe to call repeatedly: an existing installation is never replaced.
     */
    public TerminalDiagnostics initialize() {
        Installation installation = ensureInstallation();
        Optional<BootstrapConfiguration> bootstrap = bootstrapConfiguration.load();
        Optional<TerminalProvisioning> provisioning = repository.findProvisioning();

        return new TerminalDiagnostics(
                installation.installationId(),
                provisioning.isPresent() ? ProvisioningStatus.PROVISIONED : ProvisioningStatus.NOT_PROVISIONED,
                provisioning.map(TerminalProvisioning::terminalId),
                provisioning.map(TerminalProvisioning::branchId),
                databaseLocation,
                bootstrap.isPresent() ? BootstrapState.LOADED : BootstrapState.ABSENT,
                bootstrapConfiguration.location(),
                bootstrap.flatMap(BootstrapConfiguration::environment),
                bootstrap.flatMap(BootstrapConfiguration::apiBaseUrl),
                ApplicationMetadata.version(),
                System.getProperty("os.name", "") + " " + System.getProperty("os.version", "")
        );
    }

    public Installation ensureInstallation() {
        return repository.ensureInstallation(new Installation(installationIdGenerator.get(), clock.instant()));
    }

    public ProvisioningStatus provisioningStatus() {
        return repository.findProvisioning().isPresent()
                ? ProvisioningStatus.PROVISIONED
                : ProvisioningStatus.NOT_PROVISIONED;
    }

    /**
     * Stores backend-validated references for this installation, replacing previous ones atomically.
     */
    public TerminalProvisioning provision(String terminalId, String branchId) {
        TerminalProvisioning provisioning = new TerminalProvisioning(terminalId, branchId, clock.instant());
        ensureInstallation();
        repository.saveProvisioning(provisioning);
        return provisioning;
    }
}
