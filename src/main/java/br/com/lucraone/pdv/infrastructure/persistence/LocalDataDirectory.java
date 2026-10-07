package br.com.lucraone.pdv.infrastructure.persistence;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Resolves the Windows operational directory without falling back to development paths.
 */
public final class LocalDataDirectory {

    private static final String LOCAL_APP_DATA = "LOCALAPPDATA";
    private static final String APPLICATION_VENDOR = "LucraOne";
    private static final String APPLICATION_NAME = "PDV";
    private static final String DATABASE_FILE = "lucraone-pdv.db";
    private static final String BOOTSTRAP_CONFIGURATION_FILE = "bootstrap.properties";

    private final Supplier<String> localAppDataSupplier;

    public LocalDataDirectory() {
        this(() -> System.getenv(LOCAL_APP_DATA));
    }

    LocalDataDirectory(Supplier<String> localAppDataSupplier) {
        this.localAppDataSupplier = Objects.requireNonNull(localAppDataSupplier, "localAppDataSupplier must not be null");
    }

    public Path dataDirectory() {
        return applicationDirectory().resolve("data");
    }

    public Path logsDirectory() {
        return applicationDirectory().resolve("logs");
    }

    public Path configDirectory() {
        return applicationDirectory().resolve("config");
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

    private Path applicationDirectory() {
        String localAppData = localAppDataSupplier.get();
        if (localAppData == null || localAppData.isBlank()) {
            throw new LocalDatabaseException(
                    "LOCALAPPDATA não está disponível; o banco local do PDV não pode ser localizado com segurança."
            );
        }

        try {
            return Path.of(localAppData)
                    .toAbsolutePath()
                    .normalize()
                    .resolve(APPLICATION_VENDOR)
                    .resolve(APPLICATION_NAME);
        } catch (InvalidPathException exception) {
            throw new LocalDatabaseException("LOCALAPPDATA contém um caminho inválido para o banco local do PDV.", exception);
        }
    }
}
