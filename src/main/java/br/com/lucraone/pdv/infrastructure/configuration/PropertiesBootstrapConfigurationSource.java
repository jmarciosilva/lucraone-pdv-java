package br.com.lucraone.pdv.infrastructure.configuration;

import br.com.lucraone.pdv.application.terminal.BootstrapConfiguration;
import br.com.lucraone.pdv.application.terminal.BootstrapConfigurationSource;
import br.com.lucraone.pdv.application.terminal.InvalidBootstrapConfigurationException;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the optional UTF-8 Java properties bootstrap file. Validation is syntactic only: the API address is
 * never contacted. Error messages never echo configured values.
 */
public final class PropertiesBootstrapConfigurationSource implements BootstrapConfigurationSource {

    static final String ENVIRONMENT = "environment";
    static final String API_BASE_URL = "api.base-url";

    private static final Set<String> SUPPORTED_KEYS = Set.of(ENVIRONMENT, API_BASE_URL);
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");
    // Literal loopback hosts only; URI.getHost() keeps IPv6 brackets. No name resolution is performed.
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final Path file;

    public PropertiesBootstrapConfigurationSource(Path file) {
        this.file = Objects.requireNonNull(file, "file must not be null").toAbsolutePath().normalize();
    }

    @Override
    public String location() {
        return file.toString();
    }

    @Override
    public Optional<BootstrapConfiguration> load() {
        if (Files.notExists(file)) {
            return Optional.empty();
        }

        Properties properties = read();
        if (!SUPPORTED_KEYS.containsAll(properties.stringPropertyNames())) {
            throw new InvalidBootstrapConfigurationException(
                    "A configuração bootstrap contém chaves não suportadas. Use somente "
                            + ENVIRONMENT + " e " + API_BASE_URL + "."
            );
        }

        return Optional.of(new BootstrapConfiguration(environment(properties), apiBaseUrl(properties)));
    }

    private Properties read() {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return properties;
        } catch (IOException | IllegalArgumentException exception) {
            throw new InvalidBootstrapConfigurationException(
                    "Não foi possível ler a configuração bootstrap em " + file + ".",
                    exception
            );
        }
    }

    private static Optional<String> environment(Properties properties) {
        Optional<String> environment = value(properties, ENVIRONMENT);
        if (environment.isPresent() && !ENVIRONMENT_NAME.matcher(environment.get()).matches()) {
            throw new InvalidBootstrapConfigurationException(
                    ENVIRONMENT + " deve ter de 1 a 32 caracteres: letras, números, ponto, hífen ou sublinhado."
            );
        }
        return environment;
    }

    private static Optional<URI> apiBaseUrl(Properties properties) {
        return value(properties, API_BASE_URL).map(PropertiesBootstrapConfigurationSource::parseApiBaseUrl);
    }

    private static URI parseApiBaseUrl(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            // The cause is omitted because its message repeats the configured value.
            throw new InvalidBootstrapConfigurationException(API_BASE_URL + " não é uma URL válida.");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            throw new InvalidBootstrapConfigurationException(API_BASE_URL + " deve ser uma URL absoluta https (http somente para loopback local).");
        }
        if (uri.getHost() == null) {
            throw new InvalidBootstrapConfigurationException(API_BASE_URL + " deve informar um host válido.");
        }
        if (scheme.equalsIgnoreCase("http") && !LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw new InvalidBootstrapConfigurationException(
                    API_BASE_URL + " deve usar https; http só é aceito para localhost, 127.0.0.1 ou [::1]."
            );
        }
        if (uri.getRawUserInfo() != null) {
            throw new InvalidBootstrapConfigurationException(API_BASE_URL + " não pode conter credenciais.");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new InvalidBootstrapConfigurationException(API_BASE_URL + " não pode conter query string ou fragmento.");
        }
        return uri;
    }

    private static Optional<String> value(Properties properties, String key) {
        return Optional.ofNullable(properties.getProperty(key))
                .map(String::strip)
                .filter(value -> !value.isEmpty());
    }
}
