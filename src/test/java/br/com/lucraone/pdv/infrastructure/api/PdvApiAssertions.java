package br.com.lucraone.pdv.infrastructure.api;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.lucraone.pdv.application.api.PdvApiException;
import br.com.lucraone.pdv.application.api.PdvApiFailure;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Shared helpers to unwrap the typed failure carried by a future that completed exceptionally.
 */
final class PdvApiAssertions {

    /** Short deadline so a stalled backend fails the test instead of hanging the suite. */
    static final Duration TEST_REQUEST_TIMEOUT = Duration.ofMillis(400);

    static PdvApiFailure failureOf(CompletableFuture<?> future) {
        CompletionException thrown = assertThrows(CompletionException.class, future::join);
        PdvApiException exception = assertInstanceOf(PdvApiException.class, thrown.getCause());
        return exception.failure();
    }

    private PdvApiAssertions() {
    }
}
