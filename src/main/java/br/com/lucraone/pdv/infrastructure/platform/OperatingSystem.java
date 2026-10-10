package br.com.lucraone.pdv.infrastructure.platform;

import java.util.Locale;

public enum OperatingSystem {
    WINDOWS, LINUX, MACOS, UNSUPPORTED;

    public static OperatingSystem current() { return fromName(System.getProperty("os.name", "")); }

    public static OperatingSystem fromName(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("windows")) return WINDOWS;
        if (normalized.equals("linux")) return LINUX;
        if (normalized.startsWith("mac") || normalized.equals("darwin")) return MACOS;
        return UNSUPPORTED;
    }

    public static String description() { return current().name() + " " + System.getProperty("os.version", ""); }
}
