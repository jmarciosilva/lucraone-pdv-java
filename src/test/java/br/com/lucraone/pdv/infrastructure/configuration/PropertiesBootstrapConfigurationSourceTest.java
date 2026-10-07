package br.com.lucraone.pdv.infrastructure.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.application.terminal.BootstrapConfiguration;
import br.com.lucraone.pdv.application.terminal.InvalidBootstrapConfigurationException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PropertiesBootstrapConfigurationSourceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsEmptyWhenTheFileDoesNotExist() {
        assertEquals(Optional.empty(), source().load());
    }

    @Test
    void readsEnvironmentAndApiBaseUrl() throws IOException {
        write("""
                environment=homologation
                api.base-url=https://api.example.test/pdv
                """);

        BootstrapConfiguration configuration = source().load().orElseThrow();

        assertEquals(Optional.of("homologation"), configuration.environment());
        assertEquals(Optional.of(URI.create("https://api.example.test/pdv")), configuration.apiBaseUrl());
    }

    @Test
    void stripsWhitespaceAndTreatsBlankValuesAsAbsent() throws IOException {
        write("environment =   development   \napi.base-url =    \n");

        BootstrapConfiguration configuration = source().load().orElseThrow();

        assertEquals(Optional.of("development"), configuration.environment());
        assertEquals(Optional.empty(), configuration.apiBaseUrl());
    }

    @Test
    void acceptsAnEmptyFile() throws IOException {
        write("");

        assertEquals(
                Optional.of(new BootstrapConfiguration(Optional.empty(), Optional.empty())),
                source().load()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://api.example.test",
            "http://localhost:8000/api",
            "http://LOCALHOST",
            "http://127.0.0.1:8000",
            "http://[::1]:8000"
    })
    void acceptsHttpsAndHttpOnlyForLoopback(String url) throws IOException {
        write("api.base-url=" + url + "\n");

        assertEquals(Optional.of(URI.create(url)), source().load().orElseThrow().apiBaseUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://api.example.test",
            "http://192.168.0.10:8000",
            "http://127.0.0.2",
            "http://localhost.example.test",
            "not a url",
            "/relative/path",
            "ftp://api.example.test",
            "https:opaque",
            "https://operator:s3cr3t-value@api.example.test",
            "https://api.example.test/?token=s3cr3t-value",
            "https://api.example.test/#s3cr3t-value"
    })
    void rejectsInvalidApiBaseUrlsWithoutEchoingTheValue(String url) throws IOException {
        write("api.base-url=" + url + "\n");

        InvalidBootstrapConfigurationException exception =
                assertThrows(InvalidBootstrapConfigurationException.class, () -> source().load());

        assertTrue(exception.getMessage().contains("api.base-url"));
        assertFalse(exception.getMessage().contains(url));
        assertFalse(exception.getMessage().contains("s3cr3t-value"));
    }

    @Test
    void rejectsAnInvalidEnvironmentName() throws IOException {
        write("environment=../production\n");

        assertThrows(InvalidBootstrapConfigurationException.class, () -> source().load());
    }

    @Test
    void rejectsUnsupportedKeysWithoutEchoingThem() throws IOException {
        write("api.token=s3cr3t-value\n");

        InvalidBootstrapConfigurationException exception =
                assertThrows(InvalidBootstrapConfigurationException.class, () -> source().load());

        assertFalse(exception.getMessage().contains("s3cr3t-value"));
        assertFalse(exception.getMessage().contains("api.token"));
    }

    @Test
    void rejectsAMalformedFile() throws IOException {
        write("environment=\\uZZZZ\n");

        assertThrows(InvalidBootstrapConfigurationException.class, () -> source().load());
    }

    @Test
    void rejectsAFileThatIsNotUtf8() throws IOException {
        Files.write(file(), new byte[] {'e', 'n', 'v', '=', (byte) 0xC3, (byte) 0x28});

        assertThrows(InvalidBootstrapConfigurationException.class, () -> source().load());
    }

    private PropertiesBootstrapConfigurationSource source() {
        return new PropertiesBootstrapConfigurationSource(file());
    }

    private Path file() {
        return temporaryDirectory.resolve("bootstrap.properties");
    }

    private void write(String content) throws IOException {
        Files.writeString(file(), content, StandardCharsets.UTF_8);
    }
}
