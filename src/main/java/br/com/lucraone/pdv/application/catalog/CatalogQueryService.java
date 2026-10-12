package br.com.lucraone.pdv.application.catalog;

import br.com.lucraone.pdv.application.api.TerminalContext;
import br.com.lucraone.pdv.application.api.PdvApiException;
import br.com.lucraone.pdv.application.api.PdvApiFailure;
import br.com.lucraone.pdv.application.api.PdvFailureKind;
import br.com.lucraone.pdv.application.operator.OperatorSessionService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Authenticated read-only catalog use case. A cache write failure never deletes or replaces other rows. */
public final class CatalogQueryService {
    private final OperatorSessionService operator;
    private final CatalogCacheRepository cache;
    private final Supplier<Optional<TerminalContext>> contextSupplier;
    private final Clock clock;
    private final Deque<Instant> requestTimes = new ArrayDeque<>();

    public CatalogQueryService(OperatorSessionService operator, CatalogCacheRepository cache,
            Supplier<Optional<TerminalContext>> contextSupplier, Clock clock) {
        this.operator = Objects.requireNonNull(operator); this.cache = Objects.requireNonNull(cache);
        this.contextSupplier = Objects.requireNonNull(contextSupplier); this.clock = Objects.requireNonNull(clock);
    }

    public CompletableFuture<CatalogPage> search(String search, int perPage, String cursor) {
        if (!reserveRequest()) return rateLimited();
        return operator.catalogProducts(search, perPage, cursor).thenApply(page -> {
            store(page.products());
            return page;
        });
    }

    public CompletableFuture<BarcodeResolution> resolveBarcode(String barcode) {
        if (!reserveRequest()) return rateLimited();
        return operator.resolveBarcode(barcode).thenApply(result -> {
            store(List.of(result.product()));
            return result;
        });
    }

    private void store(List<CatalogProduct> products) {
        Optional<TerminalContext> context = contextSupplier.get();
        context.ifPresent(value -> cache.upsert(CatalogCacheContext.from(value), products, clock.instant()));
    }

    private synchronized boolean reserveRequest() {
        Instant cutoff = clock.instant().minusSeconds(60);
        while (!requestTimes.isEmpty() && !requestTimes.peekFirst().isAfter(cutoff)) requestTimes.removeFirst();
        if (requestTimes.size() >= 120) return false;
        requestTimes.addLast(clock.instant());
        return true;
    }

    private static <T> CompletableFuture<T> rateLimited() {
        return CompletableFuture.failedFuture(new PdvApiException(PdvApiFailure.of(PdvFailureKind.RATE_LIMITED, null)));
    }
}
