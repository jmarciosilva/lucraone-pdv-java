package br.com.lucraone.pdv.infrastructure.platform;

import java.util.Objects;
import java.util.function.Function;

public final class PlatformPathsFactory {
    private PlatformPathsFactory() {}
    public static PlatformPaths current() { return create(OperatingSystem.current(), System::getenv); }
    public static PlatformPaths create(OperatingSystem os, Function<String, String> environment) {
        Objects.requireNonNull(environment);
        return switch (Objects.requireNonNull(os)) {
            case WINDOWS -> new WindowsPlatformPaths(environment);
            case LINUX -> new LinuxPlatformPaths(environment);
            case MACOS -> new MacOsPlatformPaths(environment);
            case UNSUPPORTED -> throw new PlatformException("Sistema operacional não suportado pelo PDV.");
        };
    }
}
