package br.com.lucraone.pdv.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.application.auth.CredentialRotationRepository;
import br.com.lucraone.pdv.application.auth.CredentialRotationRepository.PendingRotation;
import br.com.lucraone.pdv.infrastructure.platform.WindowsPlatformPaths;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcCredentialRotationRepositoryTest {

    @TempDir Path temporaryDirectory;

    private JdbcCredentialRotationRepository repository() {
        var directory = new LocalDataDirectory(
                () -> new WindowsPlatformPaths(key -> temporaryDirectory.toString()));
        var connections = new LocalDatabaseInitializer(directory).initialize();
        return new JdbcCredentialRotationRepository(new JdbcTransactionManager(connections));
    }

    @Test
    void loadReturnsEmptyWhenNoRotationPersisted() {
        assertTrue(repository().load().isEmpty());
    }

    @Test
    void saveAndLoadReturnsSamePendingRotation() {
        var repo = repository();
        var rotation = new PendingRotation("00000000-0000-4000-8000-000000000001",
                Instant.parse("2026-10-11T10:00:00Z"));
        repo.save(rotation);
        PendingRotation loaded = repo.load().orElseThrow();
        assertEquals(rotation.operationId(), loaded.operationId());
        assertEquals(rotation.startedAt(), loaded.startedAt());
    }

    @Test
    void pendingOperationSurvivesRepositoryReconstruction() {
        var directory = new LocalDataDirectory(
                () -> new WindowsPlatformPaths(key -> temporaryDirectory.toString()));
        var connections = new LocalDatabaseInitializer(directory).initialize();
        var transactions = new JdbcTransactionManager(connections);
        var firstProcessRepository = new JdbcCredentialRotationRepository(transactions);
        var rotation = new PendingRotation("00000000-0000-4000-8000-000000000003",
                Instant.parse("2026-10-11T12:00:00Z"));
        firstProcessRepository.save(rotation);

        var restartedRepository = new JdbcCredentialRotationRepository(transactions);
        assertEquals(rotation, restartedRepository.load().orElseThrow());
    }

    @Test
    void saveTwiceReplacesTheRow() {
        var repo = repository();
        repo.save(new PendingRotation("00000000-0000-4000-8000-000000000001",
                Instant.parse("2026-10-11T10:00:00Z")));
        var second = new PendingRotation("00000000-0000-4000-8000-000000000002",
                Instant.parse("2026-10-11T11:00:00Z"));
        repo.save(second);
        PendingRotation loaded = repo.load().orElseThrow();
        assertEquals(second.operationId(), loaded.operationId());
    }

    @Test
    void deleteRemovesTheRow() {
        var repo = repository();
        repo.save(new PendingRotation("00000000-0000-4000-8000-000000000001",
                Instant.parse("2026-10-11T10:00:00Z")));
        repo.delete();
        assertTrue(repo.load().isEmpty());
    }

    @Test
    void deleteIsIdempotentWhenEmpty() {
        var repo = repository();
        repo.delete(); // Must not throw
        assertTrue(repo.load().isEmpty());
    }
}
