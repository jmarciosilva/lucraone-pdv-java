package br.com.lucraone.pdv.infrastructure.platform;

import java.nio.file.Path;

public interface PlatformPaths {
    Path applicationDirectory();
    default Path dataDirectory() { return applicationDirectory().resolve("data"); }
    default Path logsDirectory() { return applicationDirectory().resolve("logs"); }
    default Path configDirectory() { return applicationDirectory().resolve("config"); }
    /** Only Windows persists encrypted blobs here; native keyrings do not use it. */
    default Path secretsDirectory() { return applicationDirectory().resolve("secrets"); }
}
