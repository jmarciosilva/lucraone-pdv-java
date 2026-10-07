package br.com.lucraone.pdv.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.domain.terminal.Installation;
import br.com.lucraone.pdv.domain.terminal.TerminalProvisioning;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcTerminalRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00.123456Z");

    @TempDir
    Path temporaryDirectory;

    private LocalDataDirectory directory;
    private SqliteConnectionFactory connections;
    private JdbcTerminalRepository repository;

    @BeforeEach
    void setUp() {
        directory = new LocalDataDirectory(() -> temporaryDirectory.toString());
        connections = new LocalDatabaseInitializer(directory).initialize();
        repository = new JdbcTerminalRepository(new JdbcTransactionManager(connections));
    }

    @Test
    void storesTheFirstInstallationAndKeepsItOnLaterCalls() throws SQLException {
        Installation first = new Installation(UUID.randomUUID(), NOW);

        assertEquals(first, repository.ensureInstallation(first));
        assertEquals(first, repository.ensureInstallation(new Installation(UUID.randomUUID(), NOW.plusSeconds(60))));
        assertEquals(1, countRows("installation"));
    }

    @Test
    void keepsTheInstallationAfterReopeningTheDatabase() {
        Installation stored = repository.ensureInstallation(new Installation(UUID.randomUUID(), NOW));

        SqliteConnectionFactory reopened = new LocalDatabaseInitializer(directory).initialize();
        JdbcTerminalRepository reopenedRepository = new JdbcTerminalRepository(new JdbcTransactionManager(reopened));

        assertEquals(Optional.of(stored), reopenedRepository.findInstallation());
    }

    @Test
    void concurrentInitializationKeepsASingleInstallation() throws Exception {
        int contenders = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Installation>> tasks = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            tasks.add(() -> {
                start.await();
                return repository.ensureInstallation(new Installation(UUID.randomUUID(), Instant.now()));
            });
        }

        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Installation>> results = new ArrayList<>();
            for (Callable<Installation> task : tasks) {
                results.add(executor.submit(task));
            }
            start.countDown();

            Set<Installation> installations = new HashSet<>();
            for (Future<Installation> result : results) {
                installations.add(result.get());
            }
            assertEquals(1, installations.size());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, countRows("installation"));
    }

    @Test
    void startsWithoutProvisioning() {
        repository.ensureInstallation(new Installation(UUID.randomUUID(), NOW));

        assertTrue(repository.findProvisioning().isEmpty());
    }

    @Test
    void readsTheProvisioningAfterReopeningTheDatabase() {
        repository.ensureInstallation(new Installation(UUID.randomUUID(), NOW));
        TerminalProvisioning provisioning = new TerminalProvisioning("terminal-7", "branch-3", NOW);
        repository.saveProvisioning(provisioning);

        SqliteConnectionFactory reopened = new LocalDatabaseInitializer(directory).initialize();
        JdbcTerminalRepository reopenedRepository = new JdbcTerminalRepository(new JdbcTransactionManager(reopened));

        assertEquals(Optional.of(provisioning), reopenedRepository.findProvisioning());
    }

    @Test
    void replacesTheProvisioningAsAWhole() throws SQLException {
        repository.ensureInstallation(new Installation(UUID.randomUUID(), NOW));
        repository.saveProvisioning(new TerminalProvisioning("terminal-7", "branch-3", NOW));
        TerminalProvisioning updated = new TerminalProvisioning("terminal-8", "branch-4", NOW.plusSeconds(60));

        repository.saveProvisioning(updated);

        assertEquals(Optional.of(updated), repository.findProvisioning());
        assertEquals(1, countRows("terminal_provisioning"));
    }

    @Test
    void refusesProvisioningBeforeTheInstallationExists() throws SQLException {
        assertThrows(
                IllegalStateException.class,
                () -> repository.saveProvisioning(new TerminalProvisioning("terminal-7", "branch-3", NOW))
        );

        assertEquals(0, countRows("terminal_provisioning"));
    }

    @Test
    void schemaRejectsPartialProvisioning() throws SQLException {
        Installation installation = repository.ensureInstallation(new Installation(UUID.randomUUID(), NOW));

        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO terminal_provisioning (singleton, installation_id, terminal_id, branch_id, provisioned_at)
                    VALUES (1, '%s', 'terminal-7', NULL, '2026-10-07T12:00:00Z')
                    """.formatted(installation.installationId())));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO terminal_provisioning (singleton, installation_id, terminal_id, branch_id, provisioned_at)
                    VALUES (1, '%s', '   ', 'branch-3', '2026-10-07T12:00:00Z')
                    """.formatted(installation.installationId())));
        }

        assertEquals(0, countRows("terminal_provisioning"));
    }

    private int countRows(String table) throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }
}
