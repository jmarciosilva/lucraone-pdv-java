package br.com.lucraone.pdv.infrastructure.platform;

import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.util.function.Function;

public final class WindowsPlatformPaths implements PlatformPaths {
    private final Path root;
    public WindowsPlatformPaths(Function<String, String> environment) {
        String base = environment.apply("LOCALAPPDATA");
        if (base == null || base.isBlank()) throw new PlatformException("LOCALAPPDATA não está disponível.");
        try { root = Path.of(base).toAbsolutePath().normalize().resolve("LucraOne").resolve("PDV"); }
        catch (InvalidPathException e) { throw new PlatformException("LOCALAPPDATA contém um caminho inválido."); }
    }
    public Path applicationDirectory() { return root; }
}
