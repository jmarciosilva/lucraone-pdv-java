package br.com.lucraone.pdv.infrastructure.persistence;

import java.io.IOException;
import br.com.lucraone.pdv.infrastructure.platform.PlatformPaths;
import br.com.lucraone.pdv.infrastructure.platform.PlatformPathsFactory;
import br.com.lucraone.pdv.infrastructure.platform.PlatformException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Prepares operational directories resolved by the platform adapter.
 */
public final class LocalDataDirectory {

    private static final String DATABASE_FILE = "lucraone-pdv.db";
    private static final String BOOTSTRAP_CONFIGURATION_FILE = "bootstrap.properties";

    private final Supplier<PlatformPaths> paths;

    public LocalDataDirectory() { this(PlatformPathsFactory::current); }
    public LocalDataDirectory(PlatformPaths paths) { this(() -> Objects.requireNonNull(paths)); }
    LocalDataDirectory(Supplier<PlatformPaths> paths) { this.paths = Objects.requireNonNull(paths); }

    private PlatformPaths paths() {
        try { return paths.get(); }
        catch (PlatformException e) { throw new LocalDatabaseException(e.getMessage(), e); }
    }

    public Path dataDirectory() {
        return paths().dataDirectory();
    }

    public Path logsDirectory() {
        return paths().logsDirectory();
    }

    public Path configDirectory() {
        return paths().configDirectory();
    }

    public Path databasePath() {
        return dataDirectory().resolve(DATABASE_FILE);
    }

    public Path bootstrapConfigurationFile() {
        return configDirectory().resolve(BOOTSTRAP_CONFIGURATION_FILE);
    }

    public void prepareOperationalDirectories() {
        try {
            Files.createDirectories(dataDirectory());
            Files.createDirectories(logsDirectory());
            Files.createDirectories(configDirectory());
        } catch (IOException exception) {
            throw new LocalDatabaseException("Não foi possível preparar o diretório operacional local do PDV.", exception);
        }
    }

}
