package br.com.lucraone.pdv.infrastructure.security;

import br.com.lucraone.pdv.application.security.*;
import br.com.lucraone.pdv.infrastructure.platform.*;
import java.util.Objects;

public final class SecretStoreFactory {
    private SecretStoreFactory() {}
    public static SecretStore current() {
        OperatingSystem os = OperatingSystem.current();
        return create(os, os == OperatingSystem.WINDOWS ? PlatformPathsFactory.current() : null);
    }
    public static SecretStore create(OperatingSystem os, PlatformPaths paths) {
        return switch (Objects.requireNonNull(os)) {
            case WINDOWS -> new WindowsSecretStore(Objects.requireNonNull(paths).secretsDirectory(), new WindowsDpapiSecretProtector());
            case LINUX -> new LinuxSecretStore();
            case MACOS -> new MacOsSecretStore();
            case UNSUPPORTED -> throw new SecretStoreException(SecretStoreException.Kind.UNAVAILABLE);
        };
    }
}
