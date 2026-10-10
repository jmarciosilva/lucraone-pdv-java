package br.com.lucraone.pdv.infrastructure.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Normalised base address of the backend, resolved from the phase 3 bootstrap configuration.
 *
 * <p>Endpoints are built through {@link URI}'s multi-argument constructor rather than by string
 * concatenation, so a trailing slash, a context path or an explicit port cannot produce a malformed URL.
 *
 * <p>The transport rule matches the bootstrap rule exactly: TLS is required, and plain {@code http} is
 * accepted only for a literal loopback host. Local tests therefore need no weaker production rule.
 */
public final class PdvApiBaseUrl {

    // Literal loopback hosts only; URI.getHost() keeps IPv6 brackets. No name resolution is performed.
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final String scheme;
    private final String authority;
    private final String basePath;

    private PdvApiBaseUrl(String scheme, String authority, String basePath) {
        this.scheme = scheme;
        this.authority = authority;
        this.basePath = basePath;
    }

    public static PdvApiBaseUrl of(URI uri) {
        Objects.requireNonNull(uri, "uri must not be null");

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            throw new IllegalArgumentException("The API base URL must be an absolute https URL.");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("The API base URL must declare a valid host.");
        }
        if (scheme.equalsIgnoreCase("http") && !LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "The API base URL must use https; http is accepted only for localhost, 127.0.0.1 or [::1]."
            );
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("The API base URL must not carry credentials.");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("The API base URL must not carry a query string or a fragment.");
        }

        return new PdvApiBaseUrl(
                scheme.toLowerCase(Locale.ROOT),
                uri.getAuthority(),
                withoutTrailingSlash(uri.getPath())
        );
    }

    private static String withoutTrailingSlash(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String trimmed = path;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * @param endpointPath absolute endpoint path, always starting with {@code /}
     */
    public URI resolve(String endpointPath) {
        Objects.requireNonNull(endpointPath, "endpointPath must not be null");
        if (!endpointPath.startsWith("/")) {
            throw new IllegalArgumentException("The endpoint path must start with '/'.");
        }

        try {
            return new URI(scheme, authority, basePath + endpointPath, null, null);
        } catch (URISyntaxException exception) {
            // The message omits the value because it may carry configured data.
            throw new IllegalArgumentException("The API base URL cannot address the requested endpoint.", exception);
        }
    }
}
