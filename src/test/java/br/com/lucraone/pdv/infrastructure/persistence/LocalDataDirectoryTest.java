package br.com.lucraone.pdv.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDataDirectoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesTheDatabasePathFromLocalAppData() {
        LocalDataDirectory directory = new LocalDataDirectory(() -> temporaryDirectory.toString());

        Path expected = temporaryDirectory.toAbsolutePath().normalize()
                .resolve("LucraOne")
                .resolve("PDV")
                .resolve("data")
                .resolve("lucraone-pdv.db");

        assertEquals(expected, directory.databasePath());
    }

    @Test
    void createsTheOperationalDirectories() {
        LocalDataDirectory directory = new LocalDataDirectory(() -> temporaryDirectory.toString());

        directory.prepareOperationalDirectories();

        assertTrue(Files.isDirectory(directory.dataDirectory()));
        assertTrue(Files.isDirectory(directory.logsDirectory()));
        assertTrue(Files.isDirectory(directory.configDirectory()));
        assertFalse(Files.exists(directory.databasePath()));
    }

    @Test
    void failsExplicitlyWhenLocalAppDataIsUnavailable() {
        LocalDataDirectory directory = new LocalDataDirectory(() -> null);

        LocalDatabaseException exception = assertThrows(
                LocalDatabaseException.class,
                directory::dataDirectory
        );

        assertTrue(exception.getMessage().contains("LOCALAPPDATA"));
    }
}
