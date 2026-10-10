package br.com.lucraone.pdv.infrastructure.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the multiplatform architecture by reading the source tree directly: the inner layers must stay
 * free of transport, UI and operating-system detail. A plain source scan keeps this check dependency-free
 * instead of pulling in an architecture-testing framework.
 */
class ApiArchitectureBoundaryTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "br", "com", "lucraone", "pdv");

    /** Anything that would tie an inner layer to a transport, a UI toolkit or a concrete adapter. */
    private static final List<String> FORBIDDEN_IN_INNER_LAYERS = List.of(
            "java.net.http",
            "com.fasterxml.jackson",
            "javafx.",
            "java.sql",
            "org.flywaydb",
            "br.com.lucraone.pdv.infrastructure",
            "br.com.lucraone.pdv.presentation"
    );

    /** Operating-system detail belongs exclusively to infrastructure. */
    private static final List<String> FORBIDDEN_OS_TOKENS = List.of(
            "LOCALAPPDATA",
            "APPDATA",
            "XDG_",
            "CryptProtectData",
            "CryptUnprotectData",
            "libsecret",
            "Keychain"
    );

    private static List<Path> sourcesUnder(String layer) {
        Path root = SOURCE_ROOT.resolve(layer);
        assertTrue(Files.isDirectory(root), "layer not found: " + root.toAbsolutePath());
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<String> violations(String layer, List<String> forbidden) {
        List<String> found = new ArrayList<>();
        for (Path file : sourcesUnder(layer)) {
            String source = read(file);
            for (String token : forbidden) {
                if (source.contains(token)) {
                    found.add(file + " contains " + token);
                }
            }
        }
        return found;
    }

    @Test
    void theDomainDependsOnNoTransportUiOrAdapter() {
        assertEquals(List.of(), violations("domain", FORBIDDEN_IN_INNER_LAYERS));
    }

    @Test
    void theApplicationDependsOnNoTransportUiOrAdapter() {
        assertEquals(List.of(), violations("application", FORBIDDEN_IN_INNER_LAYERS));
    }

    @Test
    void theDomainCarriesNoOperatingSystemDetail() {
        assertEquals(List.of(), violations("domain", FORBIDDEN_OS_TOKENS));
    }

    @Test
    void theApplicationCarriesNoOperatingSystemDetail() {
        assertEquals(List.of(), violations("application", FORBIDDEN_OS_TOKENS));
    }

    @Test
    void theDomainNeverInspectsTheOperatingSystemName() {
        assertEquals(List.of(), violations("domain", List.of("os.name")));
    }

    @Test
    void thePresentationNeverTalksHttpItself() {
        List<String> found = violations("presentation", List.of("java.net.http", "com.fasterxml.jackson"));

        assertEquals(List.of(), found, "the UI must call the application port, never the network directly");
    }

    @Test
    void theApplicationApiPortDoesNotNameConcreteHttpHeaders() {
        String port = read(SOURCE_ROOT.resolve("application/api/PdvBackendGateway.java"));

        assertFalse(port.contains("Authorization"));
        assertFalse(port.contains("X-Request-ID"));
    }
}
