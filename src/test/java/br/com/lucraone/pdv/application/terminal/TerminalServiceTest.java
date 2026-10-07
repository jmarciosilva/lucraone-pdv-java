package br.com.lucraone.pdv.application.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.application.terminal.TerminalDiagnostics.BootstrapState;
import br.com.lucraone.pdv.domain.terminal.ProvisioningStatus;
import br.com.lucraone.pdv.infrastructure.configuration.PropertiesBootstrapConfigurationSource;
import br.com.lucraone.pdv.infrastructure.persistence.DatabaseMigrator;
import br.com.lucraone.pdv.infrastructure.persistence.JdbcTerminalRepository;
import br.com.lucraone.pdv.infrastructure.persistence.JdbcTransactionManager;
import br.com.lucraone.pdv.infrastructure.persistence.SqliteConnectionFactory;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerminalServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    private Path databasePath;
    private Path bootstrapFile;

    @BeforeEach
    void setUp() throws IOException {
        databasePath = Files.createDirectories(temporaryDirectory.resolve("data")).resolve("terminal.db");
        bootstrapFile = temporaryDirectory.resolve("bootstrap.properties");
        new DatabaseMigrator(new SqliteConnectionFactory(databasePath).jdbcUrl()).migrate();
    }

    @Test
    void firstInitializationCreatesARandomCanonicalInstallationId() {
        UUID installationId = newService().initialize().installationId();

        assertEquals(4, installationId.version());
        assertEquals(installationId, UUID.fromString(installationId.toString()));
    }

    @Test
    void repeatedInitializationKeepsTheSameInstallationId() {
        UUID first = newService().initialize().installationId();

        assertEquals(first, newService().initialize().installationId());
        assertEquals(first, newService().ensureInstallation().installationId());
    }

    @Test
    void startsAsNotProvisioned() {
        TerminalDiagnostics diagnostics = newService().initialize();

        assertEquals(ProvisioningStatus.NOT_PROVISIONED, diagnostics.provisioningStatus());
        assertEquals(Optional.empty(), diagnostics.terminalId());
        assertEquals(Optional.empty(), diagnostics.branchId());
    }

    @Test
    void provisioningIsStoredAndReportedAfterRestart() {
        newService().provision("  terminal-7 ", "branch-3");

        TerminalDiagnostics diagnostics = newService().initialize();

        assertEquals(ProvisioningStatus.PROVISIONED, diagnostics.provisioningStatus());
        assertEquals(Optional.of("terminal-7"), diagnostics.terminalId());
        assertEquals(Optional.of("branch-3"), diagnostics.branchId());
    }

    @Test
    void incompleteProvisioningIsRejectedAndNothingIsStored() {
        TerminalService service = newService();

        assertThrows(IllegalArgumentException.class, () -> service.provision("terminal-7", "   "));
        assertThrows(NullPointerException.class, () -> service.provision(null, "branch-3"));

        assertEquals(ProvisioningStatus.NOT_PROVISIONED, service.provisioningStatus());
    }

    @Test
    void diagnosticsDescribeTheLocalEnvironmentWithoutBootstrap() {
        TerminalDiagnostics diagnostics = newService().initialize();

        assertEquals(BootstrapState.ABSENT, diagnostics.bootstrapState());
        assertEquals(bootstrapFile.toAbsolutePath().normalize().toString(), diagnostics.bootstrapLocation());
        assertEquals(databasePath.toString(), diagnostics.databaseLocation());
        assertEquals(Optional.empty(), diagnostics.environment());
        assertFalse(diagnostics.applicationVersion().isBlank());
        assertFalse(diagnostics.operatingSystem().isBlank());
    }

    @Test
    void diagnosticsReportTheLoadedBootstrap() throws IOException {
        Files.writeString(bootstrapFile, "environment=homologation\napi.base-url=https://api.example.test\n");

        TerminalDiagnostics diagnostics = newService().initialize();

        assertEquals(BootstrapState.LOADED, diagnostics.bootstrapState());
        assertEquals(Optional.of("homologation"), diagnostics.environment());
        assertEquals(Optional.of(URI.create("https://api.example.test")), diagnostics.apiBaseUrl());
    }

    @Test
    void invalidBootstrapFailsWithoutReplacingTheInstallation() throws IOException {
        UUID installationId = newService().initialize().installationId();
        Files.writeString(bootstrapFile, "api.base-url=ftp://api.example.test\n");

        assertThrows(InvalidBootstrapConfigurationException.class, () -> newService().initialize());

        assertEquals(installationId, newService().ensureInstallation().installationId());
    }

    @Test
    void diagnosticsContainOnlyNonSensitiveFields() {
        Set<String> fields = Arrays.stream(TerminalDiagnostics.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "installationId", "provisioningStatus", "terminalId", "branchId", "databaseLocation",
                "bootstrapState", "bootstrapLocation", "environment", "apiBaseUrl", "applicationVersion",
                "operatingSystem"
        ), fields);
        String description = newService().initialize().toString().toLowerCase();
        assertTrue(description.contains("installationid"));
        assertFalse(description.contains("token"));
        assertFalse(description.contains("password"));
        assertFalse(description.contains("secret"));
    }

    private TerminalService newService() {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(databasePath);
        return new TerminalService(
                new JdbcTerminalRepository(new JdbcTransactionManager(connections)),
                new PropertiesBootstrapConfigurationSource(bootstrapFile),
                databasePath.toString(),
                CLOCK,
                UUID::randomUUID
        );
    }
}
