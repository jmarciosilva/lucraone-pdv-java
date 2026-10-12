package br.com.lucraone.pdv.application.catalog;

import br.com.lucraone.pdv.application.api.TerminalContext;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Operational product search backed only by the currently active local SQLite snapshot. */
public final class OfflineCatalogQueryService {
    private final ActiveCatalogRepository catalog;
    private final Supplier<Optional<TerminalContext>> contextSupplier;
    private final Executor worker;

    public OfflineCatalogQueryService(ActiveCatalogRepository catalog,
            Supplier<Optional<TerminalContext>> contextSupplier, Executor worker) {
        this.catalog = Objects.requireNonNull(catalog);
        this.contextSupplier = Objects.requireNonNull(contextSupplier);
        this.worker = Objects.requireNonNull(worker);
    }

    public CompletableFuture<CatalogPage> search(String search, int perPage, String cursor) {
        if (search == null || search.isBlank() || search.length() > 100) throw new IllegalArgumentException("search must contain 1 to 100 characters");
        if (perPage < 1 || perPage > 100) throw new IllegalArgumentException("perPage must be between 1 and 100");
        return CompletableFuture.supplyAsync(() -> catalog.search(currentContext(), search, perPage, cursor), worker);
    }

    public CompletableFuture<BarcodeResolution> resolveBarcode(String barcode) {
        if (barcode == null || barcode.isEmpty() || barcode.length() > 14) throw new IllegalArgumentException("barcode must contain 1 to 14 characters");
        return CompletableFuture.supplyAsync(() -> catalog.resolveBarcode(currentContext(), barcode), worker);
    }

    private CatalogCacheContext currentContext() {
        return contextSupplier.get().map(CatalogCacheContext::from)
                .orElseThrow(() -> new IllegalStateException("Authenticated terminal context is unavailable."));
    }
}
