package br.com.lucraone.pdv.infrastructure.platform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class PlatformPathsTest {
    private PlatformPaths paths(OperatingSystem os, Map<String, String> env) {
        return PlatformPathsFactory.create(os, env::get);
    }
    @Test void detectsWindows() { assertEquals(OperatingSystem.WINDOWS, OperatingSystem.fromName("Windows 11")); }
    @Test void detectsLinux() { assertEquals(OperatingSystem.LINUX, OperatingSystem.fromName("Linux")); }
    @Test void detectsMacOs() { assertEquals(OperatingSystem.MACOS, OperatingSystem.fromName("Mac OS X")); }
    @Test void detectsDarwinWithoutMistakingItForWindows() { assertEquals(OperatingSystem.MACOS, OperatingSystem.fromName("Darwin")); }
    @Test void detectsUnknown() { assertEquals(OperatingSystem.UNSUPPORTED, OperatingSystem.fromName("FreeBSD")); }
    @Test void detectsNull() { assertEquals(OperatingSystem.UNSUPPORTED, OperatingSystem.fromName(null)); }
    @Test void windowsPreservesItsTree() {
        var p = paths(OperatingSystem.WINDOWS, Map.of("LOCALAPPDATA", "C:\\Users\\Test\\AppData\\Local"));
        assertEquals(Path.of("C:\\Users\\Test\\AppData\\Local").toAbsolutePath().normalize().resolve("LucraOne").resolve("PDV"), p.applicationDirectory());
        assertEquals(p.applicationDirectory().resolve("config"), p.configDirectory());
        assertEquals(p.applicationDirectory().resolve("secrets"), p.secretsDirectory());
    }
    @Test void windowsRejectsMissingRoot() { assertThrows(PlatformException.class, () -> paths(OperatingSystem.WINDOWS, Map.of())); }
    @Test @EnabledOnOs(OS.LINUX) void linuxUsesXdg() {
        var p = paths(OperatingSystem.LINUX, Map.of("XDG_DATA_HOME", "/tmp/test-data", "XDG_CONFIG_HOME", "/tmp/test-config"));
        assertEquals(Path.of("/tmp/test-data/LucraOne/PDV"), p.applicationDirectory());
        assertEquals(Path.of("/tmp/test-config/LucraOne/PDV"), p.configDirectory());
        assertEquals(p.applicationDirectory().resolve("data"), p.dataDirectory());
        assertEquals(p.applicationDirectory().resolve("logs"), p.logsDirectory());
    }
    @Test @EnabledOnOs(OS.LINUX) void linuxUsesHomeFallback() {
        var p = paths(OperatingSystem.LINUX, Map.of("HOME", "/home/test"));
        assertEquals(Path.of("/home/test/.local/share/LucraOne/PDV"), p.applicationDirectory());
        assertEquals(Path.of("/home/test/.config/LucraOne/PDV"), p.configDirectory());
    }
    @Test @EnabledOnOs(OS.LINUX) void linuxIgnoresRelativeXdg() {
        var p = paths(OperatingSystem.LINUX, Map.of("HOME", "/home/test", "XDG_DATA_HOME", "relative", "XDG_CONFIG_HOME", "relative"));
        assertEquals(Path.of("/home/test/.local/share/LucraOne/PDV"), p.applicationDirectory());
        assertEquals(Path.of("/home/test/.config/LucraOne/PDV"), p.configDirectory());
    }
    @Test void linuxRejectsRelativeHome() { assertThrows(PlatformException.class, () -> paths(OperatingSystem.LINUX, Map.of("HOME", "relative"))); }
    @Test void linuxRejectsMissingHome() { assertThrows(PlatformException.class, () -> paths(OperatingSystem.LINUX, Map.of())); }
    @Test @EnabledOnOs(OS.MAC) void macOsUsesApplicationSupport() {
        var p = paths(OperatingSystem.MACOS, Map.of("HOME", "/Users/test"));
        assertEquals(Path.of("/Users/test/Library/Application Support/LucraOne/PDV"), p.applicationDirectory());
        assertEquals(p.applicationDirectory().resolve("config"), p.configDirectory());
    }
    @Test void macOsRejectsMissingHome() { assertThrows(PlatformException.class, () -> paths(OperatingSystem.MACOS, Map.of())); }
    @Test void unsupportedFails() { assertThrows(PlatformException.class, () -> paths(OperatingSystem.UNSUPPORTED, Map.of())); }
}
