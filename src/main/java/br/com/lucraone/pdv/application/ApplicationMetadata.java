package br.com.lucraone.pdv.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Informações básicas da aplicação usadas pela camada de apresentação.
 */
public final class ApplicationMetadata {

    private static final String NAME = "LucraOne PDV";
    private static final String BUILD_PROPERTIES = "build.properties";
    private static final String UNKNOWN_VERSION = "desconhecida";

    private ApplicationMetadata() {
    }

    public static String name() {
        return NAME;
    }

    public static String version() {
        Properties properties = new Properties();
        try (InputStream input = ApplicationMetadata.class.getResourceAsStream(BUILD_PROPERTIES)) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException exception) {
            return UNKNOWN_VERSION;
        }

        String version = properties.getProperty("version", "").strip();
        return version.isEmpty() || version.startsWith("${") ? UNKNOWN_VERSION : version;
    }
}
