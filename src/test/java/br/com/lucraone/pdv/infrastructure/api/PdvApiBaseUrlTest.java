package br.com.lucraone.pdv.infrastructure.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

class PdvApiBaseUrlTest {

    @Test
    void resolvesEndpointsUnderAPlainHttpsHost() {
        PdvApiBaseUrl baseUrl = PdvApiBaseUrl.of(URI.create("https://exemplo.com.br"));

        assertEquals(
                URI.create("https://exemplo.com.br/api/v1/pdv/health"),
                baseUrl.resolve("/api/v1/pdv/health")
        );
    }

    @Test
    void normalisesATrailingSlashInsteadOfDoublingIt() {
        PdvApiBaseUrl baseUrl = PdvApiBaseUrl.of(URI.create("https://exemplo.com.br/"));

        assertEquals(
                URI.create("https://exemplo.com.br/api/v1/pdv/health"),
                baseUrl.resolve("/api/v1/pdv/health")
        );
    }

    @Test
    void preservesAContextPathInsteadOfDiscardingIt() {
        PdvApiBaseUrl baseUrl = PdvApiBaseUrl.of(URI.create("https://exemplo.com.br/lucraone/"));

        assertEquals(
                URI.create("https://exemplo.com.br/lucraone/api/v1/pdv/health"),
                baseUrl.resolve("/api/v1/pdv/health")
        );
    }

    @Test
    void keepsAnExplicitPort() {
        PdvApiBaseUrl baseUrl = PdvApiBaseUrl.of(URI.create("https://exemplo.com.br:8443"));

        assertEquals(
                URI.create("https://exemplo.com.br:8443/api/v1/pdv/terminal"),
                baseUrl.resolve("/api/v1/pdv/terminal")
        );
    }

    @Test
    void acceptsHttpOnlyForLoopbackSoLocalTestsNeedNoWeakerRule() {
        assertEquals(
                URI.create("http://127.0.0.1:9123/api/v1/pdv/health"),
                PdvApiBaseUrl.of(URI.create("http://127.0.0.1:9123")).resolve("/api/v1/pdv/health")
        );
        assertEquals(
                URI.create("http://localhost:9123/api/v1/pdv/health"),
                PdvApiBaseUrl.of(URI.create("http://localhost:9123")).resolve("/api/v1/pdv/health")
        );
    }

    @Test
    void rejectsPlainHttpAgainstARemoteHost() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PdvApiBaseUrl.of(URI.create("http://exemplo.com.br"))
        );
    }

    @Test
    void rejectsSchemesThatAreNotHttp() {
        assertThrows(IllegalArgumentException.class, () -> PdvApiBaseUrl.of(URI.create("ftp://exemplo.com.br")));
        assertThrows(IllegalArgumentException.class, () -> PdvApiBaseUrl.of(URI.create("/apenas/caminho")));
    }

    @Test
    void rejectsCredentialsQueryStringAndFragment() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PdvApiBaseUrl.of(URI.create("https://usuario:senha@exemplo.com.br"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PdvApiBaseUrl.of(URI.create("https://exemplo.com.br?token=abc"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PdvApiBaseUrl.of(URI.create("https://exemplo.com.br#fragmento"))
        );
    }

    @Test
    void refusesARelativeEndpointPath() {
        PdvApiBaseUrl baseUrl = PdvApiBaseUrl.of(URI.create("https://exemplo.com.br"));

        assertThrows(IllegalArgumentException.class, () -> baseUrl.resolve("api/v1/pdv/health"));
    }
}
