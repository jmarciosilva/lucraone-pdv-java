package br.com.lucraone.pdv.infrastructure.platform;

import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.util.function.Function;

public final class MacOsPlatformPaths implements PlatformPaths {
    private final Path root;
    public MacOsPlatformPaths(Function<String, String> environment) {
        String home = environment.apply("HOME");
        try {
            if (home == null || home.isBlank() || !Path.of(home).isAbsolute())
                throw new PlatformException("HOME absoluto é necessário para resolver os diretórios macOS.");
            root = Path.of(home).normalize().resolve("Library").resolve("Application Support").resolve("LucraOne").resolve("PDV");
        } catch (InvalidPathException e) { throw new PlatformException("HOME contém um caminho inválido."); }
    }
    public Path applicationDirectory() { return root; }
}
