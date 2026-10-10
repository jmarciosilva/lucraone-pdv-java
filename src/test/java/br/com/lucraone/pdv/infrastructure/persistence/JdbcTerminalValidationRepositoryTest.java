package br.com.lucraone.pdv.infrastructure.persistence;

import br.com.lucraone.pdv.application.auth.TerminalValidation;
import br.com.lucraone.pdv.domain.terminal.Installation;
import br.com.lucraone.pdv.domain.terminal.TerminalProvisioning;
import br.com.lucraone.pdv.infrastructure.platform.WindowsPlatformPaths;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JdbcTerminalValidationRepositoryTest {
    @TempDir Path temporaryDirectory;
    LocalDataDirectory directory;
    SqliteConnectionFactory connections;
    JdbcTerminalRepository terminals;
    JdbcTerminalValidationRepository validations;
    Installation installation;
    static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    @BeforeEach void setUp() {
        directory = new LocalDataDirectory(() -> new WindowsPlatformPaths(key -> temporaryDirectory.toString()));
        connections = new LocalDatabaseInitializer(directory).initialize();
        terminals = new JdbcTerminalRepository(new JdbcTransactionManager(connections));
        installation = terminals.ensureInstallation(new Installation(UUID.randomUUID(), NOW));
        validations = new JdbcTerminalValidationRepository(new JdbcTransactionManager(connections));
    }
    TerminalValidation proof() {
        return new TerminalValidation(UUID.randomUUID(), installation.installationId(), "terminal-1", "branch-1", "https://example.test", NOW);
    }
    @Test void initiallyMissing() { assertTrue(validations.load().isEmpty()); }
    @Test void persistsEvidenceAcrossRestart() {
        var proof = proof(); validations.save(proof);
        var reopened = new LocalDatabaseInitializer(directory).initialize();
        assertEquals(Optional.of(proof), new JdbcTerminalValidationRepository(new JdbcTransactionManager(reopened)).load());
    }
    @Test void replacesWholeEvidence() {
        validations.save(proof()); var replacement = proof(); validations.save(replacement);
        assertEquals(Optional.of(replacement), validations.load());
    }
    @Test void invalidationIsDurableAndIdempotent() {
        validations.save(proof()); validations.invalidate(); validations.invalidate();
        assertTrue(new JdbcTerminalValidationRepository(new JdbcTransactionManager(connections)).load().isEmpty());
        assertEquals(Optional.of(installation), terminals.findInstallation());
    }
    @Test void rejectsEvidenceForMissingInstallation() {
        var wrong = new TerminalValidation(UUID.randomUUID(), UUID.randomUUID(), "t", "b", "https://example.test", NOW);
        assertThrows(LocalDatabaseException.class, () -> validations.save(wrong));
        assertTrue(validations.load().isEmpty());
    }
    @Test void corruptionFailsExplicitly() throws Exception {
        validations.save(proof());
        try (var c=connections.openConnection(); var s=c.createStatement()) {
            s.executeUpdate("UPDATE terminal_validation SET validated_at='invalid'");
        }
        assertThrows(LocalDatabaseException.class, validations::load);
    }
    @Test void schemaContainsOnlyNonSecretMetadata() throws Exception {
        validations.save(proof());
        var columns = new HashSet<String>();
        try (var c=connections.openConnection(); var s=c.createStatement(); var r=s.executeQuery("PRAGMA table_info(terminal_validation)")) {
            while(r.next()) columns.add(r.getString("name"));
        }
        assertEquals(Set.of("singleton","credential_generation","installation_id","terminal_id","branch_id","backend_identity","validated_at"), columns);
    }
    @Test void migrationPreservesVersionTwoInstallationAndProvisioning() throws Exception {
        var oldDir = new LocalDataDirectory(() -> new WindowsPlatformPaths(key -> temporaryDirectory.resolve("old").toString()));
        oldDir.prepareOperationalDirectories();
        var old = new SqliteConnectionFactory(oldDir.databasePath());
        Flyway.configure().dataSource(old.jdbcUrl(),null,null).locations("classpath:db/migration").target("2").cleanDisabled(true).load().migrate();
        var repo = new JdbcTerminalRepository(new JdbcTransactionManager(old));
        repo.ensureInstallation(installation);
        var refs = new TerminalProvisioning("t", "b", NOW); repo.saveProvisioning(refs);
        new LocalDatabaseInitializer(oldDir).initialize();
        assertEquals(Optional.of(installation), repo.findInstallation());
        assertEquals(Optional.of(refs), repo.findProvisioning());
        try(var c=old.openConnection(); var s=c.createStatement(); var r=s.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success=1 AND version IN ('1','2','3')")) {
            r.next(); assertEquals(3,r.getInt(1));
        }
    }
}
