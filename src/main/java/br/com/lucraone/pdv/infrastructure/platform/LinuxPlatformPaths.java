package br.com.lucraone.pdv.infrastructure.platform;

import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.util.function.Function;

public final class LinuxPlatformPaths implements PlatformPaths {
    private final Path root;
    private final Path config;
    public LinuxPlatformPaths(Function<String, String> environment) {
        root = application(xdg(environment, "XDG_DATA_HOME", ".local", "share"));
        config = application(xdg(environment, "XDG_CONFIG_HOME", ".config"));
    }
    private static Path xdg(Function<String, String> env, String variable, String... fallback) {
        Path configured = absolute(env.apply(variable));
        if (configured != null) return configured;
        Path home = absolute(env.apply("HOME"));
        if (home == null) throw new PlatformException("HOME absoluto é necessário para resolver os diretórios Linux.");
        for (String part : fallback) home = home.resolve(part);
        return home;
    }
    private static Path absolute(String value) {
        if (value == null || value.isBlank()) return null;
        try { Path p = Path.of(value); return p.isAbsolute() ? p.normalize() : null; }
        catch (InvalidPathException e) { return null; }
    }
    private static Path application(Path base) { return base.resolve("LucraOne").resolve("PDV"); }
    public Path applicationDirectory() { return root; }
    public Path configDirectory() { return config; }
}
