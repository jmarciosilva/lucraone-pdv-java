package br.com.lucraone.pdv.application.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.application.api.TerminalContext;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OfflineCatalogQueryServiceTest {

    private static final TerminalContext CONTEXT = new TerminalContext(
            new TerminalContext.Terminal("terminal-1", "Caixa", "active", "install-1"),
            new TerminalContext.Tenant("tenant-1", "Tenant"),
            new TerminalContext.Company("company-1", "Company"),
            new TerminalContext.Branch("branch-1", "Branch", "01"));

    @Test
    void activeCatalogInfoReturnsRepositoryValueScopedToVerifiedContext() {
        ActiveCatalogInfo info = new ActiveCatalogInfo(2, Instant.parse("2026-10-12T22:34:07Z"), 1);
        FakeCatalog catalog = new FakeCatalog(Optional.of(info));
        OfflineCatalogQueryService service =
                new OfflineCatalogQueryService(catalog, () -> Optional.of(CONTEXT), Runnable::run);

        Optional<ActiveCatalogInfo> result = service.activeCatalogInfo().join();

        assertEquals(Optional.of(info), result);
        assertEquals(new CatalogCacheContext("terminal-1", "tenant-1", "company-1"), catalog.lastContext);
    }

    @Test
    void activeCatalogInfoIsEmptyWhenNoTerminalContextIsVerified() {
        FakeCatalog catalog = new FakeCatalog(Optional.empty());
        OfflineCatalogQueryService service =
                new OfflineCatalogQueryService(catalog, Optional::empty, Runnable::run);

        assertTrue(service.activeCatalogInfo().join().isEmpty());
    }

    private static final class FakeCatalog implements ActiveCatalogRepository {
        private final Optional<ActiveCatalogInfo> info;
        private CatalogCacheContext lastContext;

        private FakeCatalog(Optional<ActiveCatalogInfo> info) { this.info = info; }

        @Override public Optional<ActiveCatalogInfo> activeCatalogInfo(CatalogCacheContext context) {
            this.lastContext = context;
            return info;
        }

        @Override public Optional<CatalogProduct> findById(CatalogCacheContext context, String productId) {
            throw new AssertionError("not expected");
        }

        @Override public CatalogPage search(CatalogCacheContext context, String search, int perPage, String cursor) {
            throw new AssertionError("not expected");
        }

        @Override public BarcodeResolution resolveBarcode(CatalogCacheContext context, String barcode) {
            throw new AssertionError("not expected");
        }
    }
}
